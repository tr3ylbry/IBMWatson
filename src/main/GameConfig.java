package main;

import jakarta.json.JsonObject;
import java.io.IOException;
import java.nio.file.Path;

/** Local operator settings are separate from reusable collection and private pack files. */
public final class GameConfig {
    public final PackCatalog catalog;
    public final Path indexBase;
    final Path configPath, catalogPath;
    final String configSha256, catalogSha256;
    public final int indexType, scorer, port;
    private GameConfig(PackCatalog catalog, Path indexBase, int indexType, int scorer, int port, Path configPath, Path catalogPath, String configHash, String catalogHash) {
        this.catalog = catalog; this.indexBase = indexBase; this.indexType = indexType; this.scorer = scorer; this.port = port;
        this.configPath = configPath; this.catalogPath = catalogPath; this.configSha256 = configHash; this.catalogSha256 = catalogHash;
    }
    public static GameConfig load(Path file) throws IOException {
        byte[] configBytes = IntakeFiles.read(file.toRealPath(), 4 * 1024 * 1024);
        JsonObject row = IntakeFiles.parse(IntakeFiles.utf8(configBytes), "game configuration");
        PackCatalog.keys(row, "game configuration", "schemaVersion id version catalog indexBase indexType scorer port");
        PackCatalog.require(PackCatalog.integer(row, "schemaVersion") == 1, "Unsupported game configuration schemaVersion");
        PackCatalog.require(PackCatalog.string(row, "id", 100).matches("[a-z][a-z0-9-]*"), "Invalid game configuration ID");
        PackCatalog.require(PackCatalog.string(row, "version", 40).matches("[0-9]+\\.[0-9]+\\.[0-9]+"), "Invalid game configuration version");
        Path parent = file.toAbsolutePath().getParent();
        int type = PackCatalog.integer(row, "indexType"), scorer = PackCatalog.integer(row, "scorer"), port = PackCatalog.integer(row, "port");
        PackCatalog.require(type >= 1 && type <= 3 && scorer >= 1 && scorer <= 4 && port >= 1 && port <= 65535,
                "Configuration requires indexType 1-3, scorer 1-4, port 1-65535");
        Path catalogFile = parent.resolve(PackCatalog.string(row, "catalog", 500)).normalize();
        byte[] catalogBytes = IntakeFiles.read(catalogFile.toRealPath(), 4 * 1024 * 1024); PackCatalog catalog = PackCatalog.load(catalogFile);
        PackCatalog.require(java.util.Arrays.equals(configBytes, IntakeFiles.read(file.toRealPath(), 4 * 1024 * 1024))
                && java.util.Arrays.equals(catalogBytes, IntakeFiles.read(catalogFile.toRealPath(), 4 * 1024 * 1024)), "Configuration/catalog changed while loading; retry startup");
        return new GameConfig(catalog, parent.resolve(PackCatalog.string(row, "indexBase", 500)).normalize(), type, scorer, port,
                file.toAbsolutePath().normalize(), catalogFile, PackCatalog.sha256(configBytes), PackCatalog.sha256(catalogBytes));
    }
}
