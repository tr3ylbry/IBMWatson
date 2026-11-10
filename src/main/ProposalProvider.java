package main;

import jakarta.json.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import static main.PackCatalog.require;

/** Server-only provider boundary. No fixture/provider selector is exposed over HTTP. */
interface ProposalProvider extends AutoCloseable {
    int MAX_INPUT = 65536, MAX_OUTPUT = 8192, MAX_RESPONSE = 128 * 1024, TIMEOUT_SECONDS = 60;
    record Reply(int status, String requestId, byte[] body) {}
    record Settings(String identity, String model, int inputTokens, int outputTokens,
                    BigDecimal inputRate, BigDecimal outputRate, BigDecimal attemptUsd,
                    BigDecimal workspaceUsd, String pricingDate) {
        public Settings {
            if (!identity.matches("[a-z0-9-]{1,80}") || !model.matches("[a-zA-Z0-9._-]{1,120}")
                    || inputTokens < 4096 || inputTokens > MAX_INPUT || outputTokens < 256 || outputTokens > MAX_OUTPUT
                    || inputRate.signum() <= 0 || outputRate.signum() <= 0 || attemptUsd.signum() <= 0
                    || attemptUsd.compareTo(BigDecimal.TEN) > 0 || workspaceUsd.compareTo(attemptUsd) < 0
                    || workspaceUsd.compareTo(BigDecimal.valueOf(100)) > 0) throw new IllegalArgumentException("Invalid assistance settings/budgets");
            java.time.LocalDate.parse(pricingDate);
        }
        BigDecimal estimate(int input, int output) { return inputRate.multiply(BigDecimal.valueOf(input)).add(outputRate.multiply(BigDecimal.valueOf(output))).movePointLeft(6); }
        JsonObject json() { return Json.createObjectBuilder().add("provider", identity).add("model", model).add("maxInputTokens", inputTokens).add("maxOutputTokens", outputTokens)
                .add("inputUsdPerMillion", inputRate).add("outputUsdPerMillion", outputRate).add("maxAttemptUsd", attemptUsd).add("workspaceBudgetUsd", workspaceUsd)
                .add("pricingAsOf", pricingDate).add("pricingReference", "https://developers.openai.com/api/docs/pricing")
                .add("pricingNote", "Operator-supplied standard token rates; estimate, not a bill. Cache discounts, taxes and other adjustments are not applied.").build(); }
        static Settings read(JsonObject j) { return new Settings(j.getString("provider"), j.getString("model"), j.getInt("maxInputTokens"), j.getInt("maxOutputTokens"),
                j.getJsonNumber("inputUsdPerMillion").bigDecimalValue(), j.getJsonNumber("outputUsdPerMillion").bigDecimalValue(), j.getJsonNumber("maxAttemptUsd").bigDecimalValue(), j.getJsonNumber("workspaceBudgetUsd").bigDecimalValue(), j.getString("pricingAsOf")); }
    }
    Settings settings();
    Reply call(JsonObject request, WikiApi.Budget budget) throws IOException;
    default void close() {}
    default JsonObject description() {
        JsonObjectBuilder j=Json.createObjectBuilder().add("configured",settings()!=null).add("synthetic",settings()!=null&&!settings().identity().equals("openai-responses"))
                .add("message",settings()==null?"Live Assistance Unconfigured. Manual authoring/import remains available. No provider call is made.":"Explicit operator action only. Proposals remain unapproved private drafts.")
                .add("limits",Json.createObjectBuilder().add("maxClues",5).add("maxSources",12).add("sourceBytes",32768).add("perSourceBytes",16384)
                        .add("maxInputTokens",MAX_INPUT).add("maxOutputTokens",MAX_OUTPUT).add("responseBytes",MAX_RESPONSE).add("timeoutSeconds",TIMEOUT_SECONDS)
                        .add("concurrency",1).add("requestsPerAttempt",1).add("automaticRetries",0).add("explicitRetriesPerChain",1));
        if(settings()!=null)j.add("settings",settings().json()); return j.build();
    }
    static ProposalProvider environment(Map<String,String> env) {
        if ("true".equals(env.get("CLUELAB_ASSISTANCE_ENABLED"))) try {
            String key=env.getOrDefault("OPENAI_API_KEY","");require(!key.isBlank()&&key.length()<1000&&!key.contains("\n")&&!key.contains("\r"),"Credential unavailable");
            Settings s=new Settings("openai-responses",env.get("CLUELAB_ASSISTANCE_MODEL"),Integer.parseInt(env.get("CLUELAB_ASSISTANCE_INPUT_TOKENS")),Integer.parseInt(env.get("CLUELAB_ASSISTANCE_OUTPUT_TOKENS")),
                    new BigDecimal(env.get("CLUELAB_ASSISTANCE_INPUT_RATE")),new BigDecimal(env.get("CLUELAB_ASSISTANCE_OUTPUT_RATE")),new BigDecimal(env.get("CLUELAB_ASSISTANCE_ATTEMPT_USD")),new BigDecimal(env.get("CLUELAB_ASSISTANCE_WORKSPACE_USD")),env.get("CLUELAB_ASSISTANCE_PRICING_DATE"));
            return new OpenAIProposalProvider(s,key,OpenAIProposalProvider.http());
        } catch (IOException|RuntimeException ignored) { /* Incomplete configuration must not disable manual authoring. */ }
        return new ProposalProvider() {
            public Settings settings(){return null;}
            public Reply call(JsonObject request,WikiApi.Budget budget)throws IOException{throw new IOException("Live assistance is unconfigured");}
        };
    }
}
