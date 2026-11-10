package main;

import jakarta.json.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** One non-streaming Responses POST, no SDK, tools, redirects or application retries. */
final class OpenAIProposalProvider implements ProposalProvider {
    static final URI ENDPOINT=URI.create("https://api.openai.com/v1/responses");
    interface Transport extends AutoCloseable {
        Reply post(JsonObject body,String key,String attempt,WikiApi.Budget budget)throws IOException;
        default void close() {}
    }
    private final Settings settings;
    private final String key;
    private final Transport transport;
    OpenAIProposalProvider(Settings settings,String key,Transport transport){this.settings=settings;this.key=key;this.transport=transport;}
    public Settings settings(){return settings;}
    public Reply call(JsonObject request,WikiApi.Budget budget)throws IOException{return transport.post(request,key,request.getJsonObject("metadata").getString("attempt_id"),budget);}
    public void close(){transport.close();}
    static Transport http(){return transport(ENDPOINT);}
    // Package-private endpoint injection exists for loopback transport tests, never browser/configuration input.
    static Transport transport(URI endpoint){
        if(!endpoint.equals(ENDPOINT)&&!("http".equals(endpoint.getScheme())&&"127.0.0.1".equals(endpoint.getHost())))throw new IllegalArgumentException("Unsupported provider endpoint");
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
        return new Transport(){public Reply post(JsonObject body,String key,String attempt,WikiApi.Budget budget)throws IOException{
            budget.check();HttpRequest request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).header("Authorization","Bearer "+key)
                    .header("Content-Type","application/json").header("X-Client-Request-Id",attempt).POST(HttpRequest.BodyPublishers.ofByteArray(IntakeFiles.json(body))).build();
            CompletableFuture<HttpResponse<byte[]>> future=client.sendAsync(request,info->new BoundedBody());
            try {
                for(;;){budget.check();try{HttpResponse<byte[]> response=future.get(100,TimeUnit.MILLISECONDS);String id=response.headers().firstValue("x-request-id").orElse("");
                    if(!id.matches("[a-zA-Z0-9_-]{0,200}"))id="";return new Reply(response.statusCode(),id,response.body());
                }catch(TimeoutException ignored){}}
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Provider request interrupted; outcome may be unknown");}
            catch(ExecutionException e){throw new IOException("Provider transport failed; outcome may be unknown");}
            finally{future.cancel(true);}
        }
        public void close(){client.shutdownNow();}
        };
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer buffer:buffers){if((long)bytes.size()+buffer.remaining()>MAX_RESPONSE){subscription.cancel();result.completeExceptionally(new IOException("Provider response byte limit exceeded"));return;}
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
