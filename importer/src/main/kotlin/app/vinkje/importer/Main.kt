package app.vinkje.importer

import app.vinkje.deck.DeckClient
import app.vinkje.deck.LoginFlow
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private const val USAGE = """deck-todo-import: Microsoft To Do naar Nextcloud Deck

Gebruik: deck-todo-import <opdracht> [opties]

  microsoft-login <account>   Inloggen bij Microsoft, met een code op een ander apparaat
  nextcloud-login <account>   Inloggen bij Nextcloud (maakt een app-wachtwoord)
  scan                        Lijsten uit To Do ophalen naar plan.json
  run [--dry-run] [--proef]   Importeren volgens plan.json
  remove-proef                De boards van een proef-import weer verwijderen
  logout <account>            App-wachtwoord intrekken en logins van dit account wissen

<account> is een korte naam naar keuze, bijvoorbeeld anna of tom.

Opties:
  --dir <map>        standaard ~/.config/deck-todo/import
  --server <url>     Nextcloud-adres voor nextcloud-login (of zet VINKJE_SERVER)
  --client-id <id>   Microsoft-app (standaard die van Graph PowerShell)
  --tenant <naam>    standaard common
"""

fun main(args: Array<String>) {
    val opts = Options.parse(args.toList()) ?: run { System.err.print(USAGE); exitProcess(2) }
    val store = Store(opts.dir)
    try {
        when (opts.command) {
            "microsoft-login" -> microsoftLogin(store, opts)
            "nextcloud-login" -> nextcloudLogin(store, opts)
            "scan" -> scan(store, opts)
            "run" -> run(store, opts)
            "remove-proef" -> removeRehearsal(store)
            "logout" -> logout(store, opts.account())
            else -> { System.err.print(USAGE); exitProcess(2) }
        }
    } catch (e: IllegalArgumentException) {
        System.err.println("Fout: ${e.message}")
        exitProcess(1)
    }
}

private class Options(
    val command: String,
    val positional: List<String>,
    val flags: Set<String>,
    val dir: Path,
    val server: String,
    val clientId: String,
    val tenant: String,
) {
    fun account(): String = positional.singleOrNull()?.takeIf { Regex("[a-z0-9-]+").matches(it) }
        ?: throw IllegalArgumentException("geef één accountnaam (a-z, 0-9, -), bijvoorbeeld: anna")

    companion object {
        fun parse(args: List<String>): Options? {
            val command = args.firstOrNull() ?: return null
            val values = mutableMapOf<String, String>()
            val flags = mutableSetOf<String>()
            val positional = mutableListOf<String>()
            var i = 1
            while (i < args.size) {
                val a = args[i]
                when {
                    a in setOf("--dir", "--server", "--client-id", "--tenant") -> values[a] = args.getOrNull(++i) ?: return null
                    a.startsWith("--") -> flags += a
                    else -> positional += a
                }
                i++
            }
            return Options(
                command, positional, flags,
                dir = values["--dir"]?.let(Paths::get) ?: Paths.get(System.getProperty("user.home"), ".config", "deck-todo", "import"),
                server = values["--server"] ?: System.getenv("VINKJE_SERVER").orEmpty(),
                clientId = values["--client-id"] ?: GraphAuth.DEFAULT_CLIENT_ID,
                tenant = values["--tenant"] ?: "common",
            )
        }
    }
}

private fun microsoftLogin(store: Store, opts: Options) {
    val account = opts.account()
    val auth = GraphAuth(opts.clientId, opts.tenant)
    val code = auth.startDeviceCode()
    println("Open ${code.verificationUri} (mag op een telefoon) en vul deze code in: ${code.userCode}")
    println("Log in met het Microsoft-account van $account. Wachten…")
    val tokens = auth.awaitDeviceCode(code)
    val refresh = tokens.refreshToken ?: throw IllegalArgumentException("Microsoft gaf geen refresh-token terug")
    val me = GraphClient({ tokens.accessToken }).me()
    val who = listOf(me.displayName, me.userPrincipalName).filter { it.isNotBlank() }.joinToString(", ")
    store.saveMicrosoft(account, MicrosoftLogin(opts.clientId, opts.tenant, refresh, who))
    println("Ingelogd als $who.")
}

private fun nextcloudLogin(store: Store, opts: Options) {
    val account = opts.account()
    val flow = LoginFlow(userAgent = "deck-todo import")
    require(opts.server.isNotBlank()) { "geef het Nextcloud-adres met --server <url> of VINKJE_SERVER" }
    val started = flow.start(opts.server)
    println("Open deze link (mag op een telefoon) en log in als $account:\n  ${started.login}")
    println("Wachten…")
    val deadline = System.currentTimeMillis() + 20 * 60_000
    while (System.currentTimeMillis() < deadline) {
        Thread.sleep(2_000)
        val creds = flow.poll(started.poll) ?: continue
        store.saveNextcloud(account, creds)
        val me = DeckClient(creds.server, creds.loginName, creds.appPassword).currentUser()
        println("Ingelogd als ${me.displayname}. Trek het app-wachtwoord na de import in met: logout $account")
        return
    }
    throw IllegalArgumentException("niet binnen 20 minuten ingelogd")
}

/** A Graph client whose rotating refresh token is written back after every renewal. */
private fun graph(store: Store, account: String): GraphClient {
    val login = store.microsoft(account) ?: throw IllegalArgumentException("geen Microsoft-login voor '$account'; eerst: microsoft-login $account")
    val auth = GraphAuth(login.clientId, login.tenant)
    var current = login
    var token: String? = null
    return GraphClient({ renew ->
        if (token == null || renew) {
            val t = auth.refresh(current.refreshToken)
            t.refreshToken?.let { current = current.copy(refreshToken = it); store.saveMicrosoft(account, current) }
            token = t.accessToken
        }
        token!!
    })
}

private fun deck(store: Store, account: String): DeckClient {
    val c = store.nextcloud(account) ?: throw IllegalArgumentException("geen Nextcloud-login voor '$account'; eerst: nextcloud-login $account")
    return DeckClient(c.server, c.loginName, c.appPassword)
}

private fun scan(store: Store, opts: Options) {
    val accounts = opts.positional.ifEmpty { store.microsoftAccounts() }
    if (accounts.isEmpty()) throw IllegalArgumentException("nog geen Microsoft-login; eerst: microsoft-login <account>")
    val found = accounts.flatMap { account ->
        val client = graph(store, account)
        client.lists().map { list ->
            val tasks = client.tasks(list.id)
            Scan.Found(account, list, open = tasks.count { it.status != "completed" }, done = tasks.count { it.status == "completed" })
        }
    }
    val plan = Scan.merge(store.plan(), found, accounts.toSet())
    store.savePlan(plan)
    for (l in plan.lists) {
        println("%-10s %-28s %-16s %s".format(l.account, l.title.take(28), if (l.skip) "(overslaan)" else l.group ?: "(geen groep)", l.info))
    }
    println("\nOpgeslagen in ${store.planFile}.")
    println("Zet bij elke lijst \"group\" op een groep uit \"groups\" (of laat null: lijst zonder groep),")
    println("of \"skip\": true. Probeer daarna: run --dry-run, run --proef, en pas dan run.")
}

private fun run(store: Store, opts: Options) {
    val plan = store.plan() ?: throw IllegalArgumentException("nog geen plan.json; eerst: scan")
    val rehearsal = "--proef" in opts.flags
    val dryRun = "--dry-run" in opts.flags
    val problems = Importer.validate(plan, { store.microsoft(it) != null }, { store.nextcloud(it) != null })
    if (problems.isNotEmpty()) throw IllegalArgumentException("plan.json klopt nog niet:\n  " + problems.joinToString("\n  "))

    val sources = mutableMapOf<String, TodoSource>()
    val decks = mutableMapOf<String, DeckClient>()
    val state = store.state(rehearsal)
    val totals = Importer(
        plan, state,
        save = { store.saveState(rehearsal, it) },
        source = { a -> sources.getOrPut(a) { graph(store, a) } },
        deck = { a -> decks.getOrPut(a) { deck(store, a) } },
        handoff = { a -> if (store.nextcloud(a) != null) decks.getOrPut(a) { deck(store, a) } else null },
        rehearsal = rehearsal,
        dryRun = dryRun,
    ).run()

    println()
    println(if (dryRun) "Zou doen:" else "Klaar:")
    println("  ${totals.created} nieuw, ${totals.updated} bijgewerkt, ${totals.deleted} verwijderd, ${totals.unchanged} ongewijzigd")
    if (totals.conflicts > 0) println("  ${totals.conflicts} ook in Deck gewijzigd (Deck-versie behouden)")
    if (totals.gone > 0) println("  ${totals.gone} niet meer in Deck (daar verwijderd of verplaatst)")
    if (totals.mismatches > 0) println("  ${totals.mismatches} lijst(en) met een afwijkend aantal: zie ≠ hierboven")
    if (totals.collisions > 0) println("  ${totals.collisions} groep(en) overgeslagen: er bestaat al een board met die naam")
    if (totals.mismatches + totals.collisions > 0) exitProcess(1)
}

private fun removeRehearsal(store: Store) {
    val state = store.state(rehearsal = true)
    for ((key, id) in state.boards) {
        val owner = key.substringBefore('/')
        println("Verwijder board '${key.substringAfter('/')}'")
        runCatching { deck(store, owner).deleteBoard(id) }.onFailure { println("  lukte niet: ${it.message}") }
    }
    store.saveState(true, ImportState())
}

private fun logout(store: Store, account: String) {
    store.nextcloud(account)?.let { c ->
        runCatching { DeckClient(c.server, c.loginName, c.appPassword).revokeAppPassword() }
            .onSuccess { println("App-wachtwoord van $account ingetrokken.") }
            .onFailure { println("Intrekken lukte niet (${it.message}); doe het zo nodig in Nextcloud → Instellingen → Beveiliging.") }
    }
    store.forget(account)
    println("Logins van $account gewist. Microsoft-toegang intrekken kan op account.microsoft.com → Privacy → Apps.")
}
