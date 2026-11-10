package main;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkshopLaunchTest {
    @Test void defaultLaunchRemainsCompatibleAndFreshWorkspaceIsExplicit() throws Exception {
        var defaults = TopicBuilderServer.Launch.from(Map.of());
        assertEquals(Path.of("drafts/local/topic-builder"),defaults.root());
        assertEquals(8766,defaults.port());assertEquals(8787,defaults.playerPort());assertTrue(defaults.defaults());
        assertEquals(Path.of("drafts"),defaults.workspace());
        var isolated = TopicBuilderServer.Launch.from(Map.of("CLUELAB_WORKSHOP_ROOT","drafts/local/fresh-workshop", "CLUELAB_WORKSHOP_PORT","8794", "CLUELAB_ISOLATED_PLAYER_PORT","8793"));
        assertEquals(Path.of("drafts/local/fresh-workshop"),isolated.root());assertEquals(8794,isolated.port());assertEquals(8793,isolated.playerPort());assertFalse(isolated.defaults());
        assertEquals(isolated.root(),isolated.workspace());
    }
    @Test void unsafeRootsAndInvalidConflictingPortsFailBeforeCreatingAnything() {
        for(String root: new String[]{"src/main/resources/admin","data","drafts/local","drafts/local/../escape","/tmp/workshop"})
            assertThrows(IOException.class,()->TopicBuilderServer.Launch.from(Map.of("CLUELAB_WORKSHOP_ROOT",root)));
        for(String port:new String[]{"0","65536","bad","-1"})
            assertThrows(IOException.class,()->TopicBuilderServer.Launch.from(Map.of("CLUELAB_WORKSHOP_PORT",port)));
        assertThrows(IOException.class,()->TopicBuilderServer.Launch.from(Map.of("CLUELAB_WORKSHOP_PORT","8787")));
    }
    @Test void existingPlayerPortIsNeverSilentlyRewritten() throws Exception {
        GameConfig config=GameConfig.load(Path.of("data/game.json"));
        var launch=TopicBuilderServer.Launch.from(Map.of());
        launch.verifyExisting(config,Map.of());
        assertThrows(IOException.class,()->launch.verifyExisting(config,Map.of("CLUELAB_ISOLATED_PLAYER_PORT","8787")));
    }
}
