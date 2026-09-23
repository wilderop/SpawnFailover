package com.lawlessmc.spawnfailover;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Plugin(
        id = "spawnfailover",
        name = "SpawnFailover",
        version = "1.0.0",
        description = "Send players to spawn-backup only while survival is unreachable",
        authors = {"wilder0p"}
)
public final class SpawnFailoverPlugin {

    // Never name this "survival" — SkyWorlds Velocity clears fabric-resume on survival connect.
    public static final String SPAWN_BACKUP = "spawn-backup";

    private final ProxyServer proxy;
    private final Logger log;

    private final AtomicBoolean survivalUp = new AtomicBoolean(true);
    private final AtomicBoolean spawnUp = new AtomicBoolean(false);
    private final AtomicInteger survivalStreak = new AtomicInteger(0);
    private final AtomicInteger spawnStreak = new AtomicInteger(0);

    private String survivalHost = "127.0.0.1";
    private int survivalPort = 30004;
    private String spawnHost = "127.0.0.1";
    private int spawnPort = 30004;
    private String lobbyName = "lobby";
    private int fall = 3;
    private int rise = 2;
    private int timeoutMs = 1500;

    @Inject
    public SpawnFailoverPlugin(ProxyServer proxy, Logger log) {
        this.proxy = proxy;
        this.log = log;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        InetSocketAddress addr = new InetSocketAddress(spawnHost, spawnPort);
        if (proxy.getServer(SPAWN_BACKUP).isEmpty()) {
            proxy.registerServer(new ServerInfo(SPAWN_BACKUP, addr));
            log.info("Registered {} at {}:{}", SPAWN_BACKUP, spawnHost, spawnPort);
        }
        proxy.getScheduler().buildTask(this, this::probe)
                .repeat(Duration.ofSeconds(2))
                .schedule();
        log.info("SpawnFailover: survival {}:{} spawn {}:{} (never named survival)",
                survivalHost, survivalPort, spawnHost, spawnPort);
    }

    @Subscribe
    public void onChoose(PlayerChooseInitialServerEvent event) {
        if (survivalUp.get() || !spawnUp.get()) {
            return;
        }
        Optional<RegisteredServer> spawn = proxy.getServer(SPAWN_BACKUP);
        if (spawn.isEmpty()) {
            return;
        }
        log.info("survival down — {} initial server {}", event.getPlayer().getUsername(), SPAWN_BACKUP);
        event.setInitialServer(spawn.get());
    }

    @Subscribe
    public void onPreConnect(ServerPreConnectEvent event) {
        String dest = event.getOriginalServer().getServerInfo().getName();
        if (!SPAWN_BACKUP.equalsIgnoreCase(dest)) {
            return;
        }
        if (survivalUp.get()) {
            log.info("blocked {} -> {} (survival is up)", event.getPlayer().getUsername(), SPAWN_BACKUP);
            Optional<RegisteredServer> lobby = proxy.getServer(lobbyName);
            if (lobby.isPresent()) {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(lobby.get()));
            } else {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
            }
        }
    }

    private void probe() {
        update(survivalUp, survivalStreak, tcpUp(survivalHost, survivalPort), "survival");
        update(spawnUp, spawnStreak, tcpUp(spawnHost, spawnPort), SPAWN_BACKUP);
    }

    private void update(AtomicBoolean flag, AtomicInteger streak, boolean now, String name) {
        if (now == flag.get()) {
            streak.set(0);
            return;
        }
        int n = streak.incrementAndGet();
        int need = now ? rise : fall;
        if (n < need) {
            return;
        }
        streak.set(0);
        flag.set(now);
        log.info("{} is now {}", name, now ? "UP" : "DOWN");
    }

    private boolean tcpUp(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
