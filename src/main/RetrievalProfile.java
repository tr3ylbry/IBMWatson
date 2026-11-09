package main;

/** Predeclared operator-only trials. The game always uses BASELINE. */
public enum RetrievalProfile {
    BASELINE("baseline"), TITLE_CATEGORY("title-category"), COVERAGE("coverage");
    public final String id;
    RetrievalProfile(String id) { this.id = id; }
    public static RetrievalProfile from(String id) {
        for (RetrievalProfile profile : values()) if (profile.id.equals(id)) return profile;
        throw new IllegalArgumentException("Unknown retrieval profile: " + id);
    }
}
