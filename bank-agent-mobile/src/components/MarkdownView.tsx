import React from 'react';
import { Linking, ScrollView, StyleSheet, Text, View } from 'react-native';
import { parseInline, parseMarkdown, type InlineToken, type MarkdownBlock } from '@/lib/markdown';

function Inline({ tokens }: { tokens: InlineToken[] }) {
  return (
    <Text>
      {tokens.map((token, index) => {
        switch (token.type) {
          case 'strong':
            return <Text key={index} style={styles.strong}>{token.text}</Text>;
          case 'em':
            return <Text key={index} style={styles.em}>{token.text}</Text>;
          case 'code':
            return <Text key={index} style={styles.inlineCode}>{token.text}</Text>;
          case 'link':
            return (
              <Text
                key={index}
                style={styles.link}
                accessibilityRole="link"
                onPress={() => void Linking.openURL(token.href)}
              >
                {token.text}
              </Text>
            );
          default:
            return <Text key={index}>{token.text}</Text>;
        }
      })}
    </Text>
  );
}

function Table({ headers, rows }: { headers: string[]; rows: string[][] }) {
  return (
    <ScrollView horizontal style={styles.tableScroll}>
      <View style={styles.table}>
        <View style={styles.tableRow}>
          {headers.map((cell, index) => (
            <Text key={index} style={styles.th}><Inline tokens={parseInline(cell)} /></Text>
          ))}
        </View>
        {rows.map((row, rowIndex) => (
          <View key={rowIndex} style={[styles.tableRow, rowIndex % 2 === 1 && styles.tableRowAlt]}>
            {headers.map((_, colIndex) => (
              <Text key={colIndex} style={styles.td}>
                <Inline tokens={parseInline(row[colIndex] ?? '')} />
              </Text>
            ))}
          </View>
        ))}
      </View>
    </ScrollView>
  );
}

export function MarkdownView({ content }: { content: string }) {
  const blocks = React.useMemo(() => parseMarkdown(content), [content]);
  if (!blocks.length) return null;

  return (
    <View accessibilityRole="text">
      {blocks.map((block: MarkdownBlock, index: number) => {
        switch (block.type) {
          case 'heading':
            return (
              <Text key={index} style={[styles.heading, styles[`h${Math.min(block.level, 4)}` as keyof typeof styles]]}>
                <Inline tokens={parseInline(block.text)} />
              </Text>
            );
          case 'paragraph':
            return <Text key={index} style={styles.paragraph}><Inline tokens={parseInline(block.text)} /></Text>;
          case 'list':
            return (
              <View key={index} style={styles.list}>
                {block.items.map((item, itemIndex) => (
                  <View key={itemIndex} style={styles.listItem}>
                    <Text style={styles.bullet}>{block.ordered ? `${itemIndex + 1}.` : '•'}</Text>
                    <Text style={styles.listText}><Inline tokens={parseInline(item)} /></Text>
                  </View>
                ))}
              </View>
            );
          case 'code':
            return (
              <View key={index} style={styles.codeBlock}>
                <Text style={styles.codeText}>{block.text}</Text>
              </View>
            );
          case 'quote':
            return (
              <View key={index} style={styles.quote}>
                <Text style={styles.quoteText}><Inline tokens={parseInline(block.text)} /></Text>
              </View>
            );
          case 'table':
            return <Table key={index} headers={block.headers} rows={block.rows} />;
          case 'rule':
            return <View key={index} style={styles.rule} />;
          default:
            return null;
        }
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  strong: { fontWeight: '700' },
  em: { fontStyle: 'italic' },
  inlineCode: { fontFamily: 'Menlo', backgroundColor: '#E8EEF2', borderRadius: 4, paddingHorizontal: 3 },
  link: { color: '#0B6E4F', textDecorationLine: 'underline' },
  heading: { fontWeight: '700', color: '#10251B', marginTop: 10, marginBottom: 4 },
  h1: { fontSize: 22 }, h2: { fontSize: 19 }, h3: { fontSize: 17 }, h4: { fontSize: 15 },
  paragraph: { fontSize: 15, lineHeight: 21, color: '#1D2B24', marginVertical: 4 },
  list: { marginVertical: 4 },
  listItem: { flexDirection: 'row', alignItems: 'flex-start', marginVertical: 2 },
  bullet: { width: 22, fontSize: 15, color: '#0B6E4F' },
  listText: { flex: 1, fontSize: 15, lineHeight: 21, color: '#1D2B24' },
  codeBlock: { backgroundColor: '#10251B', borderRadius: 8, padding: 10, marginVertical: 6 },
  codeText: { color: '#E7F5ED', fontFamily: 'Menlo', fontSize: 13 },
  quote: { borderLeftWidth: 3, borderLeftColor: '#8BBCA6', paddingLeft: 10, marginVertical: 6 },
  quoteText: { fontStyle: 'italic', color: '#40564B' },
  tableScroll: { marginVertical: 6 },
  table: { borderWidth: 1, borderColor: '#CBDAD2', borderRadius: 6 },
  tableRow: { flexDirection: 'row' },
  tableRowAlt: { backgroundColor: '#F2F7F4' },
  th: { padding: 6, minWidth: 110, fontWeight: '700', borderRightWidth: 1, borderColor: '#CBDAD2', color: '#10251B' },
  td: { padding: 6, minWidth: 110, borderRightWidth: 1, borderColor: '#CBDAD2', color: '#1D2B24' },
  rule: { height: 1, backgroundColor: '#CBDAD2', marginVertical: 10 },
});
