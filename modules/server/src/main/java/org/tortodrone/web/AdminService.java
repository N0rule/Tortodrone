package org.tortodrone.web;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.server.*;
import org.tortodrone.storage.*;

/** Typed allowlist over authoritative domain services; never interprets console, SQL or paths. */
public final class AdminService implements AutoCloseable {
    private final AccountStore accounts;
    private final ServerControl control;
    private final Path deployment;
    /** Retained log lines; the stream reports its own bounds instead of pretending to be complete. */
    private static final int LOG_CAPACITY = 1000;

    private final ArrayDeque<ServerLog.Event> logs = new ArrayDeque<>();
    private long logsDropped;
    private final AutoCloseable subscription;
    private final NewsEditor news;
    private final WelcomeEditor welcome;
    private final AdminViews views;
    private final MetricHistory history;
    private final org.tortodrone.resources.ContentRegistry validatedContent;
    private final Path data;
    private Path logDirectory;
    private PairCheck pairCache;

    public AdminService(AccountStore accounts, ServerControl control, ServerLog log, Path deployment)
            throws IOException {
        this(
                accounts,
                control,
                log,
                deployment,
                null,
                org.tortodrone.core.config.ServerSettings.defaultPath(deployment),
                deployment.resolveSibling("data"));
    }

    public AdminService(
            AccountStore accounts,
            ServerControl control,
            ServerLog log,
            Path deployment,
            org.tortodrone.resources.ContentRegistry validatedContent)
            throws IOException {
        this(
                accounts,
                control,
                log,
                deployment,
                validatedContent,
                org.tortodrone.core.config.ServerSettings.defaultPath(deployment),
                deployment.resolveSibling("data"));
    }

    public AdminService(
            AccountStore accounts,
            ServerControl control,
            ServerLog log,
            Path deployment,
            org.tortodrone.resources.ContentRegistry validatedContent,
            Path config,
            Path data)
            throws IOException {
        this.validatedContent = validatedContent;
        this.data = data;
        this.accounts = accounts;
        this.control = control;
        this.deployment = deployment;
        news = new NewsEditor(deployment.resolve("content/shared/news"));
        welcome = new WelcomeEditor(config);
        views = new AdminViews(accounts, control, validatedContent);
        history = new MetricHistory(control);
        this.logDirectory = log.directory();
        subscription = log.subscribe(e -> {
            synchronized (logs) {
                if (logs.size() == LOG_CAPACITY) {
                    logs.removeFirst();
                    logsDropped++;
                }
                logs.addLast(e);
            }
        });
    }

    public Object read(String route, Input p) throws IOException {
        return switch (route) {
            case "status" -> {
                var s = control.snapshot();
                var status = new LinkedHashMap<String, Object>();
                status.put("version", s.version());
                status.put("state", s.state());
                status.put("endpoint", s.endpoint());
                status.put("uptimeSeconds", s.uptimeSeconds());
                status.put("metrics", s.metrics());
                status.put("output", s.output());
                status.put("database", Database.diagnostics());
                status.put("storage", accounts.storageMetrics());
                status.put("accounts", accounts.list().size());
                status.put("sessions", s.sessions());
                status.put("scenes", s.scenes());
                status.put("failure", s.failure());
                yield status;
            }
            case "accounts" -> accounts.list();
            case "knights" -> accounts.knights(p.text("username"));
            case "knight" -> accounts.worldProfile(p.text("username"), p.text("knight"));
            case "knight-state" -> views.state(p.text("username"), p.text("knight"));
            case "knight-view" -> views.knight(p.text("username"), p.text("knight"));
            case "knights-all" -> views.knights();
            case "guilds" -> views.guilds();
            case "guild" ->
                views.guild(
                        p.text("name"),
                        p.optional("knight"),
                        p.optional("count").isEmpty() ? 25 : p.integer("count"));
            case "catalogue" -> views.items(p);
            case "item" -> views.item(p.text("config"));
            case "item-definitions" -> views.definitions(p.optional("configs"));
            case "accessories" -> views.accessories(p.text("config"));
            case "item-preview" -> views.validateModifiers(p);
            case "metrics" -> history.read(p.optional("minutes").isEmpty() ? 5 : p.integer("minutes"));
            case "items" ->
                accounts.catalog().entries().stream()
                        .filter(e -> (e.config() + " " + e.displayName())
                                .toLowerCase(Locale.ROOT)
                                .contains(p.optional("search").toLowerCase(Locale.ROOT)))
                        .limit(250)
                        .map(e -> Map.of(
                                "config",
                                e.config(),
                                "name",
                                e.displayName(),
                                "category",
                                accounts.catalog().category(e),
                                "grantable",
                                e.grantable(),
                                "reason",
                                e.unavailableReason()))
                        .toList();
            case "depot" ->
                Map.of(
                        "snapshot",
                        accounts.depot().snapshot(),
                        "promotions",
                        accounts.depot().promotions(),
                        "effective",
                        accounts.depot().catalog());
            case "auctions" -> accounts.auctions().overview();
            case "deliveries" -> accounts.auctions().deliveries(p.number("id"));
            case "console" -> AdminConsole.commands();
            case "identity" -> identity();
            case "logs" -> {
                synchronized (logs) {
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("events", List.copyOf(logs));
                    payload.put("capacity", LOG_CAPACITY);
                    payload.put("dropped", logsDropped);
                    payload.put("retained", logs.size());
                    payload.put(
                            "since",
                            logs.isEmpty() ? 0 : logs.getFirst().timestamp().toEpochMilli());
                    payload.put("now", System.currentTimeMillis());
                    payload.put("file", logFile());
                    yield payload;
                }
            }
            case "logs-file" -> logTail(p.optional("limit").isEmpty() ? 500 : p.integer("limit"));
            case "news" -> news.read();
            case "mail" -> {
                var house = accounts.auctions();
                var payload = new LinkedHashMap<String, Object>();
                payload.put("now", System.currentTimeMillis());
                payload.put("campaigns", house.campaigns().list());
                payload.put("welcome", house.campaigns().welcome());
                // Shipped, not mirrored: a client-side copy of the sender union is a label
                // waiting to be invented after the server adds one.
                payload.put("senders", MailCampaignStore.SENDERS);
                payload.put("limits", mailLimits());
                yield payload;
            }
            case "mail-welcome-deliveries" ->
                Map.of("recipients", accounts.auctions().campaigns().welcomeDeliveries());
            case "mail-deliveries" ->
                Map.of(
                        "campaign", p.number("id"),
                        "recipients", accounts.auctions().campaigns().deliveries(p.number("id")));
            case "welcome" -> welcome.read();
            case "content" -> {
                var c = validatedContent == null
                        ? org.tortodrone.resources.ContentManager.load(deployment)
                        : validatedContent;
                yield Map.of(
                        "warnings",
                        c.warnings(),
                        "maps",
                        c.scenes().scenes().size(),
                        "items",
                        c.items().entries().size(),
                        "news",
                        c.news(),
                        "merchants",
                        c.town().stations());
            }
            default -> throw new IllegalArgumentException("Unknown resource");
        };
    }

    /** Fixed limits the composer mirrors, so a counter and a refusal agree. */
    private static Map<String, Object> mailLimits() {
        return Map.of(
                "subject", MailCampaignStore.MAX_SUBJECT,
                "body", MailCampaignStore.MAX_BODY,
                "units", MailCampaignStore.MAX_UNITS,
                "senderLabel", MailCampaignStore.MAX_SENDER_LABEL);
    }

    /** The on-disk log this buffer mirrors, published so the UI can say where the rest lives. */
    private Map<String, Object> logFile() {
        Path path = logDirectory == null ? null : logDirectory.resolve("latest.log");
        var file = new LinkedHashMap<String, Object>();
        file.put("path", "data/logs/latest.log");
        boolean exists = path != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        file.put("exists", exists);
        file.put("bytes", 0L);
        file.put("modified", 0L);
        if (exists) {
            try {
                file.put("bytes", Files.size(path));
                file.put("modified", Files.getLastModifiedTime(path).toMillis());
            } catch (IOException ignored) {
                // A file racing rotation is not worth failing the stream for.
            }
        }
        return file;
    }

    /** The tail of the real log file, verbatim, for operators who need more than the buffer. */
    private Object logTail(int limit) throws IOException {
        if (logDirectory == null) throw new IOException("No log directory is available");
        Path path = logDirectory.resolve("latest.log");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("No log file is available yet");
        int bounded = Math.max(1, Math.min(limit, 2000));
        var lines = new ArrayDeque<String>();
        try (var reader = Files.newBufferedReader(path, java.nio.charset.StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() == bounded) lines.removeFirst();
                lines.addLast(line);
            }
        }
        return Map.of(
                "path",
                "data/logs/latest.log",
                "lines",
                List.copyOf(lines),
                "limit",
                bounded,
                "modified",
                Files.getLastModifiedTime(path).toMillis());
    }

    /**
     * The handshake identity is public material: a remote operator needs the modulus and exponent to
     * point a client at this server without shell access. Only the public half is ever reported, and
     * the file is read defensively so a missing or huge file cannot fail the page.
     */
    private Object identity() throws IOException {
        Path directory = data == null ? null : data.resolve("keys");
        Path publicFile = directory == null ? null : directory.resolve("public.txt");
        Path privateFile = directory == null ? null : directory.resolve("private.pk8");
        var result = new LinkedHashMap<String, Object>();
        // Whether a signing key exists is asked once, so every branch tells the same truth
        // instead of a corrupt public.txt reporting that no keypair is stored at all.
        boolean keyStored = privateFile != null && Files.isRegularFile(privateFile, LinkOption.NOFOLLOW_LINKS);
        result.put("keyStored", keyStored);
        // Only the public file is named: the private half is reported as a
        // boolean and a timestamp, never as a path or a filename.
        result.put("file", "data/keys/public.txt");
        result.put("fingerprintAlgorithm", "SHA-256");
        result.put("fingerprintNote", "SHA-256 of the DER SubjectPublicKeyInfo in data/keys/public.txt");
        // The public file's timestamp is reported whether or not a signing key is
        // stored: a public.txt without its private half is still a fact worth seeing.
        try {
            result.put("publicModified", Files.getLastModifiedTime(publicFile).toMillis());
        } catch (IOException ignored) {
            // A missing timestamp is not a reason to refuse the panel.
        }
        if (keyStored) {
            try {
                result.put("keyModified", Files.getLastModifiedTime(privateFile).toMillis());
            } catch (IOException ignored) {
                // Same.
            }
        }
        if (publicFile == null
                || !Files.isRegularFile(publicFile, LinkOption.NOFOLLOW_LINKS)
                || Files.size(publicFile) > 4096) {
            result.put("available", false);
            // "No keypair yet" and "a keypair I cannot hand out" are different
            // problems with different fixes, and the panel below this sentence
            // prints which one it is: saying "no keypair" over "signing key
            // present" is the contradiction an operator would chase first.
            result.put(
                    "reason",
                    keyStored
                            ? "The signing key is stored but data/keys/public.txt is missing or unreadable; no client can be handed a key until it is restored."
                            : "No server keypair is stored yet; it is created on the next start.");
            return result;
        }
        byte[] raw = Files.readAllBytes(publicFile);
        // The hash an operator can reproduce with one plain command, unlike the DER fingerprint.
        result.put("fileBytes", raw.length);
        result.put("fileSha256", HexFormat.of().formatHex(digest(raw)));
        result.put("fileSha256Command", "sha256sum data/keys/public.txt");
        // The download hands the operator the file, not a re-encoding of it: a copy
        // restored from backup with a trailing newline or uppercase hex still starts,
        // and a re-encoded download would no longer hash to the printed value.
        String fileText = new String(raw, java.nio.charset.StandardCharsets.US_ASCII);
        if (java.util.Arrays.equals(fileText.getBytes(java.nio.charset.StandardCharsets.US_ASCII), raw))
            result.put("fileText", fileText);
        String value = new String(raw, java.nio.charset.StandardCharsets.US_ASCII).trim();
        int separator = value.indexOf('#');
        if (separator < 2 || separator == value.length() - 1) {
            result.put("available", false);
            result.put("reason", "keys/public.txt is not in modulus#exponent form.");
            return result;
        }
        String modulusHex = value.substring(0, separator).toLowerCase(Locale.ROOT);
        String exponentHex = value.substring(separator + 1).toLowerCase(Locale.ROOT);
        BigInteger modulus;
        BigInteger exponent;
        try {
            modulus = new BigInteger(modulusHex, 16);
            exponent = new BigInteger(exponentHex, 16);
        } catch (NumberFormatException invalid) {
            result.put("available", false);
            result.put("reason", "keys/public.txt does not parse as hexadecimal numbers.");
            return result;
        }
        // A handshake key is an RSA key: a large odd modulus and an odd exponent.
        // Anything else stays "unavailable" instead of failing the route later.
        if (modulus.signum() <= 0
                || !modulus.testBit(0)
                || modulus.bitLength() < 512
                || exponent.signum() <= 0
                || !exponent.testBit(0)
                || exponent.compareTo(BigInteger.valueOf(65537)) > 0) {
            result.put("available", false);
            result.put("reason", "keys/public.txt does not hold a usable RSA public key.");
            return result;
        }
        result.put("available", true);
        result.put("algorithm", "RSA");
        result.put("publicKey", modulusHex + "#" + exponentHex);
        result.put("modulusChars", modulusHex.length());
        result.put("modulus", modulusHex);
        result.put("exponent", exponentHex);
        result.put("bits", modulus.bitLength());
        byte[] der;
        try {
            der = der(modulus, exponent);
        } catch (IOException | RuntimeException invalid) {
            result.put("available", false);
            result.put("reason", "keys/public.txt does not encode as an X.509 public key.");
            return result;
        }
        result.put("fingerprint", fingerprint(der));
        result.put("fingerprintHex", HexFormat.of().formatHex(digest(der)));
        result.put("fingerprintBase64", Base64.getEncoder().encodeToString(digest(der)));
        result.put("derBase64", Base64.getEncoder().encodeToString(der));
        // Does the public half still match the private key that is actually signing handshakes?
        // A replaced file would otherwise publish a confident fingerprint for an unused key.
        PairCheck check = pair(privateFile, modulus, exponent);
        result.put("pairMatches", check.matches());
        // When the files disagree, the key that actually signs handshakes is the one an
        // operator needs, so publish what the private half implies.
        result.put("expectedPublicKey", check.expectedPublicKey());
        // Only whether a signing key exists is published; no private material
        // and no path to it ever crosses the HTTP boundary.
        result.put("bytes", raw.length);
        return result;
    }

    /**
     * The public half the private key actually implies, and whether the stored file agrees.
     *
     * <p>Parsing a PKCS#8 key on every Overview poll would be waste, so the answer is cached
     * against the private key's size and modification time: a rotation changes those, which is
     * exactly when the cache must miss.
     */
    private PairCheck pair(Path privateFile, BigInteger modulus, BigInteger exponent) {
        if (privateFile == null || !Files.isRegularFile(privateFile, LinkOption.NOFOLLOW_LINKS)) {
            return new PairCheck(null, null, null);
        }
        String stamp;
        try {
            stamp = Files.size(privateFile)
                    + ":"
                    + Files.getLastModifiedTime(privateFile).toMillis()
                    + ":"
                    + modulus.bitLength()
                    + ":"
                    + exponent;
        } catch (IOException unreadable) {
            return new PairCheck(null, null, null);
        }
        PairCheck cached = pairCache;
        if (cached != null && stamp.equals(cached.stamp())) return cached;
        PairCheck fresh;
        try {
            byte[] encoded = Files.readAllBytes(privateFile);
            if (encoded.length > 16384) throw new IOException("private key too large");
            var key = java.security.KeyFactory.getInstance("RSA")
                    .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(encoded));
            if (!(key instanceof java.security.interfaces.RSAPrivateCrtKey crt))
                throw new IOException("not an RSA private key");
            fresh = new PairCheck(
                    stamp,
                    crt.getModulus().toString(16) + "#"
                            + crt.getPublicExponent().toString(16),
                    crt.getModulus().equals(modulus) && crt.getPublicExponent().equals(exponent));
        } catch (Exception unreadable) {
            // An unreadable private key is reported as unknown, never as a mismatch.
            fresh = new PairCheck(stamp, null, null);
        }
        pairCache = fresh;
        return fresh;
    }

    /** Cached answer for {@link #pair}: the private key's own public half and its verdict. */
    private record PairCheck(String stamp, String expectedPublicKey, Boolean matches) {}

    /** X.509 SubjectPublicKeyInfo bytes, the form openssl prints a fingerprint of. */
    private static byte[] der(BigInteger modulus, BigInteger exponent) throws IOException {
        try {
            return java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(new java.security.spec.RSAPublicKeySpec(modulus, exponent))
                    .getEncoded();
        } catch (java.security.GeneralSecurityException invalid) {
            throw new IOException("Server public key cannot be encoded", invalid);
        }
    }

    private static byte[] digest(byte[] value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * Upper-case hex in byte pairs, the form anyone can compare against openssl or a
     * <code>sha256sum</code> of the modulus, and read aloud over a phone.
     */
    private static String fingerprint(byte[] der) {
        String hex = HexFormat.of().withUpperCase().formatHex(digest(der));
        var grouped = new StringBuilder(hex.length() + hex.length() / 2);
        for (int index = 0; index < hex.length(); index += 2) {
            if (index > 0) grouped.append(':');
            grouped.append(hex, index, Math.min(hex.length(), index + 2));
        }
        return grouped.toString();
    }

    public static WebIdentityStore.Role permission(String route, boolean write) {
        if (!write)
            return switch (route) {
                case "status", "logs", "logs-file", "metrics", "identity" -> WebIdentityStore.Role.VIEWER;
                case "accounts",
                        "knights",
                        "knight",
                        "items",
                        "guilds",
                        "guild",
                        "knight-state",
                        "knight-view",
                        "knights-all",
                        "catalogue",
                        "item",
                        "item-definitions",
                        "accessories",
                        "item-preview" -> WebIdentityStore.Role.MODERATOR;
                default -> WebIdentityStore.Role.ADMINISTRATOR;
            };
        return route.equals("kick") ? WebIdentityStore.Role.MODERATOR : WebIdentityStore.Role.ADMINISTRATOR;
    }

    public Object write(String route, Input p) throws IOException {
        // Browser confirmation is repeated server-side for every mutation.
        if (!p.text("confirmation").equals(route))
            throw new IllegalArgumentException("Confirmation did not match the action");
        switch (route) {
            case "console" -> {
                return AdminConsole.execute(this, p);
            }
            case "start" -> control.start();
            case "stop" -> control.stop();
            case "restart" -> control.restart();
            case "shutdown" -> {
                if (!p.text("shutdown").equals("SHUT DOWN PROCESS"))
                    throw new IllegalArgumentException("Process shutdown confirmation required");
                control.exit();
            }
            case "kick" -> {
                if (!control.kick(p.number("session")))
                    throw new IllegalArgumentException("Session already disconnected");
            }
            case "account-create" -> password(p, pw -> accounts.create(p.text("username"), pw, p.flag("gm")));
            case "account-check-password" -> {
                char[] pw = p.text("password").toCharArray();
                try {
                    return Map.of("matches", accounts.checkPassword(p.text("username"), pw));
                } finally {
                    Arrays.fill(pw, '\0');
                }
            }
            case "account-password" ->
                password(p, pw -> accounts.setPassword(p.text("username"), pw, p.positive("accountId")));
            case "account-gm" -> accounts.setGameMaster(p.text("username"), p.flag("gm"), p.positive("accountId"));
            case "account-delete" -> {
                if (!p.text("username").equals(p.text("nameConfirmation")))
                    throw new IllegalArgumentException("Account name confirmation required");
                accounts.delete(p.text("username"), p.positive("accountId"));
            }
            case "knight-delete" -> {
                if (!p.text("knight").equals(p.text("nameConfirmation")))
                    throw new IllegalArgumentException("Knight name confirmation required");
                accounts.deleteKnight(
                        p.text("username"), p.text("knight"), p.positive("accountId"), p.positive("worldId"));
            }
            case "knight-progress" -> {
                return accounts.progress(
                        p.text("username"),
                        p.text("knight"),
                        p.integer("rank"),
                        p.integer("subrank"),
                        p.integer("prestige"),
                        p.positive("accountId"),
                        p.positive("worldId"),
                        p.optional("sprite").isEmpty() ? null : p.text("sprite"),
                        p.number("revision"));
            }
            case "knight-currency" -> {
                String operation = p.text("operation");
                if (!Set.of("set", "delta").contains(operation))
                    throw new IllegalArgumentException("Invalid currency operation");
                return accounts.administrativeCurrency(
                        p.text("username"),
                        p.text("knight"),
                        org.tortodrone.core.character.Currency.valueOf(p.text("currency")),
                        p.number("amount"),
                        operation.equals("set"),
                        p.positive("accountId"),
                        p.positive("worldId"),
                        p.number("revision"));
            }
            case "item-grant" -> {
                return accounts.grantItems(
                        p.text("username"),
                        p.text("knight"),
                        p.text("config"),
                        p.positive("quantity"),
                        p.positive("accountId"),
                        p.positive("worldId"),
                        modifiers(p),
                        p.flag("bound"),
                        p.integer("expireDays"));
            }
            case "item-quantity" -> {
                return accounts.itemQuantity(
                        p.text("username"),
                        p.text("knight"),
                        p.number("itemId"),
                        p.integer("quantity"),
                        p.positive("accountId"),
                        p.positive("worldId"),
                        p.number("revision"));
            }
            case "item-accessory" -> {
                return accounts.itemAccessory(
                        p.text("username"),
                        p.text("knight"),
                        p.number("itemId"),
                        p.optional("accessory").isEmpty() ? null : p.text("accessory"),
                        p.positive("accountId"),
                        p.positive("worldId"),
                        p.number("revision"));
            }
            case "equipment" -> {
                return accounts.administrativeEquipment(
                        p.text("username"),
                        p.text("knight"),
                        p.positive("accountId"),
                        p.positive("worldId"),
                        p.number("revision"),
                        p.number("itemId"),
                        p.optional("slot").isEmpty() ? null : p.integer("slot"),
                        p.optional("grant"));
            }
            case "item-modifiers" -> {
                return accounts.itemModifiers(
                        p.text("username"),
                        p.text("knight"),
                        p.number("itemId"),
                        modifiers(p),
                        p.positive("accountId"),
                        p.positive("worldId"),
                        p.number("revision"));
            }
            case "depot-save" -> {
                return accounts.depot()
                        .save(
                                p.number("revision"),
                                new DepotStore.Offer(
                                        p.integer("id"),
                                        new TownCatalog.Good(
                                                p.text("config"),
                                                p.positive("quantity"),
                                                p.integer("crowns"),
                                                p.integer("energy"),
                                                p.integer("limit"),
                                                "",
                                                0),
                                        p.flag("featured"),
                                        p.flag("enabled"),
                                        p.flag("promotionOnly")));
            }
            case "promotion-create" -> {
                return accounts.depot()
                        .schedule(
                                p.number("revision"),
                                new DepotStore.Promotion(
                                        0,
                                        p.integer("offerId"),
                                        p.number("starts"),
                                        p.number("ends"),
                                        p.integer("crowns"),
                                        p.integer("energy"),
                                        p.optional("referencePrice").isEmpty() ? null : p.integer("referencePrice"),
                                        p.flag("featured"),
                                        false));
            }
            case "promotion-cancel" -> {
                return accounts.depot().cancelPromotion(p.number("revision"), p.number("id"));
            }
            case "auction-create" -> {
                return accounts.auctions()
                        .scheduleFeatured(
                                p.text("config"),
                                p.positive("quantity"),
                                p.integer("bid"),
                                p.integer("buy"),
                                p.integer("stock"),
                                p.number("starts"),
                                p.number("expires"));
            }
            case "auction-cancel" -> {
                return accounts.auctions().cancel(p.number("id"));
            }
            case "auction-settle" -> accounts.auctions().settleExpired();
            case "mail-save" -> {
                return accounts.auctions()
                        .campaigns()
                        .save(
                                p.number("id"),
                                p.number("revision"),
                                p.number("starts"),
                                p.number("ends"),
                                p.integer("sender"),
                                p.optional("senderLabel"),
                                p.text("subject"),
                                p.text("body"),
                                p.integer("crowns"),
                                p.integer("energy"),
                                gifts(p.text("items")));
            }
            case "mail-send" -> {
                var target = accounts.worldProfile(p.text("username"), p.text("knight"));
                return Map.of(
                        "id",
                        accounts.auctions()
                                .campaigns()
                                .sendTo(
                                        target.worldId(),
                                        p.integer("sender"),
                                        p.optional("senderLabel"),
                                        p.text("subject"),
                                        p.text("body"),
                                        p.integer("crowns"),
                                        p.integer("energy"),
                                        gifts(p.text("items"))),
                        "worldId",
                        target.worldId());
            }
            case "mail-welcome-save" -> {
                return accounts.auctions()
                        .campaigns()
                        .saveWelcome(
                                p.flag("enabled"),
                                p.integer("sender"),
                                p.optional("senderLabel"),
                                p.optional("subject"),
                                p.optional("body"),
                                p.integer("crowns"),
                                p.integer("energy"),
                                gifts(p.text("items")));
            }
            case "mail-cancel" -> {
                return accounts.auctions().campaigns().cancel(p.number("id"), p.number("revision"));
            }
            case "news-save" -> {
                return news.save(p.text("yaml"), p.text("revision"));
            }
            case "news-visual-save" -> {
                return news.saveCatalog(p.text("catalog"), p.text("revision"));
            }
            case "news-upload" -> {
                return news.upload(p.text("name"), p.text("data"));
            }
            case "welcome-save" -> {
                return welcome.save(p.optional("message"), p.text("revision"));
            }
            default -> throw new IllegalArgumentException("Unknown action");
        }
        return Map.of("ok", true);
    }

    @SuppressWarnings("unchecked")
    private static List<MailCampaignStore.Gift> gifts(String json) throws IOException {
        try {
            var rows = (List<Object>) org.tortodrone.core.Json.parse(json);
            var gifts = new ArrayList<MailCampaignStore.Gift>(rows.size());
            for (var row : rows) {
                var map = (Map<String, Object>) row;
                gifts.add(new MailCampaignStore.Gift(
                        (String) map.get("config"),
                        ((Number) map.get("count")).intValue(),
                        Boolean.TRUE.equals(map.get("bound"))));
            }
            return gifts;
        } catch (RuntimeException bad) {
            throw new IOException("Invalid gift list", bad);
        }
    }

    private static ItemModifiers modifiers(Input p) {
        return new ItemModifiers(
                p.integer("level"),
                Float.parseFloat(p.text("heat")),
                p.optional("variants").isBlank()
                        ? List.of()
                        : Arrays.stream(p.text("variants").split("\n"))
                                .map(String::strip)
                                .filter(s -> !s.isEmpty())
                                .toList());
    }

    @FunctionalInterface
    private interface PasswordAction {
        void run(char[] password) throws IOException;
    }

    private static void password(Input p, PasswordAction action) throws IOException {
        char[] pw = p.text("password").toCharArray();
        try {
            action.run(pw);
        } finally {
            Arrays.fill(pw, '\0');
        }
    }

    public record Input(Map<String, String> values) {
        public String text(String key) {
            String s = values.get(key);
            if (s == null || s.isEmpty()) throw new IllegalArgumentException("Missing " + key);
            return s;
        }

        public String optional(String key) {
            return values.getOrDefault(key, "");
        }

        public long number(String key) {
            return Long.parseLong(text(key));
        }

        public int integer(String key) {
            return Math.toIntExact(number(key));
        }

        public int positive(String key) {
            int n = integer(key);
            if (n <= 0) throw new IllegalArgumentException("Invalid " + key);
            return n;
        }

        public boolean flag(String key) {
            String v = text(key);
            if (!v.equals("true") && !v.equals("false")) throw new IllegalArgumentException("Invalid " + key);
            return Boolean.parseBoolean(v);
        }
    }

    @Override
    public void close() throws Exception {
        history.close();
        subscription.close();
    }
}
