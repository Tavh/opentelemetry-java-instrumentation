/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.okhttp.v3_0;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.testing.junit.http.AbstractHttpClientTest;
import io.opentelemetry.instrumentation.testing.junit.http.HttpClientTestOptions;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.http.HttpMethod;
import org.junit.jupiter.api.Test;

public abstract class AbstractOkHttp3Test extends AbstractHttpClientTest<Request> {

  protected abstract Call.Factory createCallFactory(OkHttpClient.Builder clientBuilder);

  protected final Call.Factory client = createCallFactory(getClientBuilder(false));
  private final Call.Factory clientWithReadTimeout = createCallFactory(getClientBuilder(true));

  OkHttpClient.Builder getClientBuilder(boolean withReadTimeout) {
    OkHttpClient.Builder builder =
        new OkHttpClient.Builder()
            .connectTimeout(CONNECTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            .protocols(singletonList(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false);
    if (withReadTimeout) {
      builder.readTimeout(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }
    return builder;
  }

  @Override
  protected Request buildRequest(String method, URI uri, Map<String, String> headers)
      throws Exception {
    RequestBody body =
        HttpMethod.requiresRequestBody(method)
            ? RequestBody.create(MediaType.parse("text/plain"), "")
            : null;
    return new Request.Builder()
        .url(uri.toURL())
        .method(method, body)
        .headers(Headers.of(headers))
        .build();
  }

  @Override
  protected int sendRequest(Request request, String method, URI uri, Map<String, String> headers)
      throws Exception {
    Response response = getClient(uri).newCall(request).execute();
    try (ResponseBody ignored = response.body()) {
      return response.code();
    }
  }

  @Override
  protected void sendRequestWithCallback(
      Request request,
      String method,
      URI uri,
      Map<String, String> headers,
      AbstractHttpClientTest.RequestResult requestResult) {
    getClient(uri)
        .newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                requestResult.complete(e);
              }

              @Override
              public void onResponse(Call call, Response response) {
                try (ResponseBody ignored = response.body()) {
                  requestResult.complete(response.code());
                }
              }
            });
  }

  private Call.Factory getClient(URI uri) {
    if (uri.toString().contains("/read-timeout")) {
      return clientWithReadTimeout;
    }
    return client;
  }

  @Override
  protected void configure(HttpClientTestOptions options) {
    options.disableTestCircularRedirects();
    options.enableTestReadTimeout();

    options.setHttpAttributes(
        uri -> {
          Set<AttributeKey<?>> attributes =
              new HashSet<>(HttpClientTestOptions.DEFAULT_HTTP_ATTRIBUTES);

          // flavor is extracted from the response, and those URLs cause exceptions (= null
          // response)
          if ("http://localhost:61/".equals(uri.toString())
              || "https://192.0.2.1/".equals(uri.toString())
              || resolveAddress("/read-timeout").toString().equals(uri.toString())) {
            attributes.remove(SemanticAttributes.HTTP_FLAVOR);
          }

          return attributes;
        });
  }

  private static class TestInterceptor implements Interceptor {

    @Override
    public Response intercept(Chain chain) throws IOException {
      // make copy of the request
      Request request = chain.request().newBuilder().build();

      return chain.proceed(request);
    }
  }

  @Test
  void requestWithCustomInterceptor() throws Throwable {
    String method = "GET";
    URI uri = resolveAddress("/success");

    RequestResult result = new RequestResult(() -> testing.runWithSpan("child", () -> {}));
    OkHttpClient.Builder builder = getClientBuilder(false);
    builder.addInterceptor(new TestInterceptor());
    Call.Factory testClient = createCallFactory(builder);

    testing.runWithSpan(
        "parent",
        () -> {
          Request request = buildRequest(method, uri, Collections.emptyMap());
          testClient
              .newCall(request)
              .enqueue(
                  new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                      result.complete(e);
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                      try (ResponseBody ignored = response.body()) {
                        result.complete(response.code());
                      }
                    }
                  });
        });

    assertThat(result.get()).isEqualTo(200);

    testing.waitAndAssertTraces(
        trace -> {
          trace.hasSpansSatisfyingExactly(
              span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
              span -> span.hasName("HTTP GET").hasKind(SpanKind.CLIENT).hasParent(trace.getSpan(0)),
              span ->
                  span.hasName("test-http-server")
                      .hasKind(SpanKind.SERVER)
                      .hasParent(trace.getSpan(1)),
              span -> span.hasName("child").hasKind(SpanKind.INTERNAL).hasParent(trace.getSpan(0)));
        });
  }
}
