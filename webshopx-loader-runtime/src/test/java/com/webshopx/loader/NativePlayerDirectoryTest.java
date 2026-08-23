package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.server.FakeConnectionEvent;
import net.minecraft.commands.FakeCommandSource;
import org.junit.jupiter.api.Test;

class NativePlayerDirectoryTest {
  @Test void indexesAndRemovesProfilesFromOpaqueNativeObjectGraphs() {
    NativePlayerDirectory directory = new NativePlayerDirectory("server-a");
    UUID id = UUID.randomUUID();
    FakeConnectionEvent event = new FakeConnectionEvent(new GameProfile(id, "Kanbara"));

    directory.joined(event);
    var found = directory.find("kanbara").toCompletableFuture().join();
    assertTrue(found.isPresent());
    assertEquals(id, found.orElseThrow().id());
    assertEquals("server-a", found.orElseThrow().serverId());
    assertEquals(1, directory.onlinePlayers().toCompletableFuture().join().size());

    directory.disconnected(event);
    assertTrue(directory.find(id).toCompletableFuture().join().isEmpty());
  }

  @Test void commandIdentityRequiresDirectPlayerEntityAndRejectsConsoleProfileCache() {
    GameProfile profile = new GameProfile(UUID.randomUUID(), "Command_Player");
    assertTrue(NativePlayerDirectory.commandSourcePlayer(
        FakeCommandSource.player(profile), "server-a").isPresent());
    assertTrue(NativePlayerDirectory.commandSourcePlayer(
        FakeCommandSource.console(profile), "server-a").isEmpty());
  }
}
