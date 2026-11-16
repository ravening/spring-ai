package com.example.deeprelay.rag;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RagIngestionService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String METADATA_SOURCE = "source";
    private static final String METADATA_INGESTED_AT = "ingestedAt";

    private static final int MIN_PDF_TEXT_CHARS = 200;

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "pdf", "doc", "docx", "odt", "rtf",
            "html", "htm", "xml", "json", "csv",
            "ppt", "pptx", "xls", "xlsx");

    private final SimpleVectorStore vectorStore;
    private final RagProperties properties;
    private final TokenTextSplitter textSplitter;

    private boolean storePopulated;

    public RagIngestionService(SimpleVectorStore vectorStore, RagProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.textSplitter = TokenTextSplitter.builder()
                .withChunkSize(properties.chunkSize())
                .build();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (loadPersistedStore()) {
            return;
        }
        if (properties.ingestClasspathDocs()) {
            try {
                ReindexResult result = reindexClasspathDocuments();
                log.info("Indexed {} classpath document(s) into {} chunk(s)", result.documents(), result.chunks());
            }
            catch (Exception ex) {
                log.error("Failed to index classpath documents", ex);
            }
        }
    }

    public synchronized IngestionResult ingest(Resource resource, String source) {
        String effectiveSource = normalizeSource(resource, source);
        List<Document> documents = readDocuments(resource, effectiveSource);
        deleteBySource(effectiveSource);
        int chunks = addDocuments(documents, effectiveSource);
        if (chunks == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No readable text was found in " + effectiveSource);
        }
        save();
        return new IngestionResult(effectiveSource, chunks);
    }

    public synchronized ReindexResult reindexClasspathDocuments() {
        List<Resource> resources = resolveClasspathDocuments();
        int chunks = 0;
        for (Resource resource : resources) {
            String source = sourceName(resource);
            try {
                List<Document> documents = readDocuments(resource, source);
                deleteBySource(source);
                chunks += addDocuments(documents, source);
            }
            catch (Exception ex) {
                log.warn("Failed to index {}", source, ex);
            }
        }
        save();
        return new ReindexResult(resources.size(), chunks);
    }

    private List<Document> readDocuments(Resource resource, String source) {
        if (this.properties.ocrEnabled() && isPdf(source)) {
            return readPdfWithOcr(resource, source);
        }
        return new TikaDocumentReader(resource).get();
    }

    private List<Document> readPdfWithOcr(Resource resource, String source) {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();

        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.OCR_AND_TEXT_EXTRACTION);
        pdfConfig.setExtractInlineImages(false);
        context.set(PDFParserConfig.class, pdfConfig);

        TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
        ocrConfig.setLanguage(this.properties.ocrLanguage());
        ocrConfig.setTimeoutSeconds(this.properties.ocrTimeoutSeconds());
        context.set(TesseractOCRConfig.class, ocrConfig);

        try (InputStream input = resource.getInputStream()) {
            parser.parse(input, handler, metadata, context);
        }
        catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not extract text from " + source + ": " + ex.getMessage(), ex);
        }

        String text = handler.toString().strip();
        if (text.length() < MIN_PDF_TEXT_CHARS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No readable text was found in " + source
                            + ". The PDF may be scanned and OCR did not detect enough text.");
        }

        Map<String, Object> metadataMap = new HashMap<>();
        for (String name : metadata.names()) {
            String value = metadata.get(name);
            if (value != null) {
                metadataMap.put(name, value);
            }
        }
        return List.of(new Document(text, metadataMap));
    }

    private static boolean isPdf(String source) {
        return source.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private int addDocuments(List<Document> documents, String source) {
        List<Document> chunks = this.textSplitter.apply(documents.stream().filter(Document::isText).toList());
        for (Document chunk : chunks) {
            chunk.getMetadata().put(METADATA_SOURCE, source);
            chunk.getMetadata().put(METADATA_INGESTED_AT, Instant.now().toString());
        }
        if (!chunks.isEmpty()) {
            this.vectorStore.add(chunks);
            this.storePopulated = true;
        }
        return chunks.size();
    }

    private void deleteBySource(String source) {
        this.vectorStore.delete(new FilterExpressionBuilder().eq(METADATA_SOURCE, source).build());
    }

    private List<Resource> resolveClasspathDocuments() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            return Arrays.stream(resolver.getResources(properties.classpathDocsLocation()))
                    .filter(Resource::isReadable)
                    .filter(resource -> resource.getFilename() != null)
                    .filter(this::isSupported)
                    .sorted(Comparator.comparing(this::sourceName))
                    .toList();
        }
        catch (IOException ex) {
            throw new UncheckedIOException("Could not resolve " + properties.classpathDocsLocation(), ex);
        }
    }

    private boolean isSupported(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null) {
            return false;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return false;
        }
        return SUPPORTED_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private boolean loadPersistedStore() {
        Path path = Path.of(properties.vectorStoreFile());
        try {
            if (Files.isRegularFile(path) && Files.size(path) > 0) {
                JsonNode root = OBJECT_MAPPER.readTree(path.toFile());
                if (root != null && root.size() > 0) {
                    this.vectorStore.load(path.toFile());
                    this.storePopulated = true;
                    log.info("Loaded vector store from {}", path.toAbsolutePath());
                    return true;
                }
                log.info("Vector store file {} is empty, rebuilding from classpath documents", path);
            }
        }
        catch (Exception ex) {
            log.warn("Could not load vector store from {}, starting with an empty store", path, ex);
        }
        return false;
    }

    private void save() {
        if (!this.storePopulated) {
            log.info("Vector store is empty, skipping persistence");
            return;
        }
        Path path = Path.of(properties.vectorStoreFile());
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.vectorStore.save(path.toFile());
        }
        catch (Exception ex) {
            log.warn("Could not persist vector store to {}", path, ex);
        }
    }

    private String normalizeSource(Resource resource, String source) {
        return StringUtils.hasText(source) ? source : sourceName(resource);
    }

    private String sourceName(Resource resource) {
        String filename = resource.getFilename();
        if (StringUtils.hasText(filename)) {
            return filename;
        }
        try {
            return resource.getURL().toString();
        }
        catch (IOException ex) {
            return resource.getDescription();
        }
    }

    public record IngestionResult(String source, int chunks) {
    }

    public record ReindexResult(int documents, int chunks) {
    }
}
