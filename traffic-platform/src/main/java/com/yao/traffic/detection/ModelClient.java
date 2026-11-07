package com.yao.traffic.detection;

import com.yao.traffic.evidence.domain.EvidenceStorage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Fixed configured destination and bounded binary request/response. No database transaction spans this call. */
@Component
public class ModelClient {
    private final EvidenceStorage storage;
    private final DetectionSettings settings;
    private final ObjectMapper json;
    private final HttpClient http;
    public ModelClient(EvidenceStorage storage,DetectionSettings settings,ObjectMapper json) {
        this.storage=storage; this.settings=settings; this.json=json;
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(settings.httpTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public DetectionModels.Result detect(DetectionModels.Claim claim) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Model HTTP must run outside a database transaction");
        byte[] content;
        try { content=storage.read(claim.objectKey()); }
        catch(RuntimeException unavailable) { throw new Failure("EVIDENCE_UNAVAILABLE",false,unavailable); }
        if(content.length==0 || content.length>5*1024*1024)throw new Failure("INVALID_EVIDENCE",false,null);
        String boundary="traffic-"+UUID.randomUUID();
        String type=claim.objectKey().endsWith(".png")?"image/png":"image/jpeg";
        byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"image\"; filename=\"evidence\"\r\nContent-Type: "+type+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] suffix=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII);
        var request=HttpRequest.newBuilder(settings.modelUrl).timeout(Duration.ofMillis(settings.httpTimeoutMs))
                .header("Content-Type","multipart/form-data; boundary="+boundary)
                .header("X-Request-Id",claim.taskId())
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofByteArray(content),HttpRequest.BodyPublishers.ofByteArray(suffix))).build();
        CompletableFuture<HttpResponse<byte[]>> pending=http.sendAsync(request,info->new LimitedBodySubscriber(65536));
        HttpResponse<byte[]> response;
        try { response=pending.get(settings.httpTimeoutMs,TimeUnit.MILLISECONDS); }
        catch(TimeoutException timeout) {
            pending.cancel(true);
            throw new Failure("MODEL_UNAVAILABLE",true,timeout);
        } catch(InterruptedException interrupted) {
            pending.cancel(true);Thread.currentThread().interrupt();
            throw new Failure("MODEL_UNAVAILABLE",true,interrupted);
        } catch(ExecutionException failure) {
            pending.cancel(true);
            Throwable cause=failure.getCause();
            for(Throwable nested=cause;nested!=null;nested=nested.getCause())
                if(nested instanceof ResponseTooLarge)throw new Failure("MODEL_RESPONSE_TOO_LARGE",false,cause);
            throw new Failure("MODEL_UNAVAILABLE",true,cause);
        }
        if(response.statusCode()!=200)throw new Failure("MODEL_HTTP_"+response.statusCode(),
                response.statusCode()==429 || response.statusCode()>=500,null);
        try {
            var body=json.readTree(response.body());
            if(!body.isObject() || !body.path("schemaVersion").isInt() || body.path("schemaVersion").intValue()!=1
                    || !body.path("score").isNumber() || !body.path("threshold").isNumber()
                    || !body.path("modelVersion").isString() || !body.path("isAiGenerated").isBoolean())
                throw new Failure("INVALID_MODEL_RESPONSE",false,null);
            var result=new DetectionModels.Result(body.path("score").asDouble(),body.path("threshold").asDouble(),
                    body.path("modelVersion").asString(),body.path("isAiGenerated").asBoolean());
            if(!result.valid())throw new Failure("INVALID_MODEL_RESPONSE",false,null);
            return result;
        } catch(Failure failure) { throw failure; }
        catch(RuntimeException malformed) { throw new Failure("INVALID_MODEL_RESPONSE",false,malformed); }
    }
    public static class Failure extends RuntimeException {
        private final String code;
        private final boolean retryable;
        public Failure(String code,boolean retryable,Throwable cause) { super(code,cause); this.code=code;this.retryable=retryable; }
        public String code(){return code;}
        public boolean retryable(){return retryable;}
    }
    private static final class ResponseTooLarge extends RuntimeException { }
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private final ByteArrayOutputStream output=new ByteArrayOutputStream();
        private final int maximum;
        private Flow.Subscription subscription;
        LimitedBodySubscriber(int maximum){this.maximum=maximum;}
        @Override public CompletableFuture<byte[]> getBody(){return body;}
        @Override public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers){
                if(buffer.remaining()>maximum-output.size()){
                    subscription.cancel();body.completeExceptionally(new ResponseTooLarge());return;
                }
                byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable throwable){body.completeExceptionally(throwable);}
        @Override public void onComplete(){body.complete(output.toByteArray());}
    }
}
