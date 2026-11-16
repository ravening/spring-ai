# DeepRelay

DeepRelay is an OpenAI-compatible serverless inference API. Chat, embeddings, image
and video generation all live under one base URL and one API key.

## API basics

- Base URL: `https://api.deeprelay.ai/v1`
- Authentication: send the API key as `Authorization: Bearer $DEEPRELAY_API_KEY`
- Chat completions: `POST /v1/chat/completions`
- Embeddings: `POST /v1/embeddings`
- Model catalog: `GET /v1/models`

Model ids are canonical `deeprelay/` names, for example `deeprelay/deepseek-v4.1-flash`
or `deeprelay/kimi-k3`. List the available ids with `GET /v1/models`.

## Chat completions

Chat follows the OpenAI chat request and response shapes. Pass `stream: true` to receive
a Server-Sent Events stream terminated by `data: [DONE]`. Tool calling and structured
output are supported on models that advertise them. Not every OpenAI parameter is
accepted: unsupported parameters can be rejected with a 400 or ignored.

## Embeddings

`POST /v1/embeddings` is synchronous. It accepts a single string or an array of up to
2048 strings and returns one float vector per input, in request order. `encoding_format`
accepts only `float`. Usage is billed on input tokens only.

An embedding model commonly used with DeepRelay is `deeprelay/qwen3-embedding-8b`,
which produces vectors with 4096 dimensions. Some models support requesting a narrower
vector with the `dimensions` parameter.

## Retrieval augmented generation

RAG combines an embedding model with a vector store. Documents are split into chunks,
each chunk is embedded, and the vectors are stored. At question time the question is
embedded, similar chunks are retrieved, and those chunks are added to the prompt as
context for the chat model.

Spring AI can use DeepRelay for both sides of this flow: the OpenAI-compatible chat
model for generation and the OpenAI-compatible embedding model for vectors.

## Operational notes

- Keys belong to production. Use `https://api.deeprelay.ai/v1` for every call.
- Billing for embeddings is metered on input tokens and settled against the credit
  balance.
- The CLI exposes the same surface: `deeprelay embeddings <model> "text"` returns a
  vector, and `deeprelay models --modality embedding` lists embedding models.
