# Craftplay Lotterie

Ein umfangreiches Lotterie-Plugin für Paper/Spigot mit Vault, GUI, PlaceholderAPI, Hologrammen, Statistiken, Webhooks und mehreren getrennten Lotterie-Profilen.

## Features

- Automatische Ziehungen mit Zeitzone, optional mehreren Ziehzeiten und Wochentagsregeln
- Ticketkauf per Befehl und GUI, inklusive konfigurierbarer Kaufbuttons
- Maximalwerte pro Kauf und pro Spieler, Cooldown, Tageslimits und Kaufbestätigung
- Jackpot, Zusatzbetrag, Steuerabzug, Gewinnsteuer, Preisstaffeln und optionaler Jackpot-Boost
- Rückerstattung bei zu wenigen Spielern, auch mit Offline-Benachrichtigung
- Mehrere Gewinner, feste Auszahlungen, Gewinnanteile und Gewinnpakete
- Getrennte Lotterie-Profile über `lotteries.yml` mit eigenen Runden, Töpfen, Historien und Ziehzeiten
- Spieler können ihr persönliches Lotterie-Profil über `/lottery profile` wählen
- Persönliche Reminder für Ziehung, Gewinn, Rückerstattung und Topf-Schwelle über `/lottery reminders`
- Persönliche “Ziehung gleich”-Erinnerung für Spieler, die bereits Tickets besitzen
- Lotterie-Typen `jackpot`, `fifty_fifty`, `fixed_prize` und optionale Item-Lotterie
- Persistente Daten für Tickets, Ausgaben, Gewinne, Gewinnerhistorie, Saisonpunkte und Offline-Zahlungen
- Statistik-GUI mit Top-10-Listen und letzten 10 Gewinnern
- Countdown- und Statistik-Hologramme mit eigenen Templates
- Saisonstatistiken, automatische Saisonwechsel, Saisonbelohnungen und Saison-Shop
- Kostenlose Tickets über `/lottery free [grund]`
- Admins können Spielern kostenlose Tickets für Events, Votes, Jobs oder Quests gewähren
- Admin-GUI, Admin-Log, Transaktionslog mit Filter, CSV/YAML-Export, Import, Backup und Doctor
- GUI-Editor und Config-Updater für fehlende Default-Keys
- Setup-Wizard, Doctor-Fix, Audit-ZIP und einfache statische Web-Übersicht
- Admin-Auswertungen für Tages-/Wochen-/Monatsumsatz, Auszahlungsquote und Steuern
- Optionales Anti-Abuse-Monitoring für auffällige Kaufspitzen
- Animiertes Ziehungs-GUI und Gewinnerwand
- Discord/Webhook-Benachrichtigungen und automatisch generierte `PLACEHOLDERS.md`
- GitHub-Release-Updatechecker mit Startprüfung, Admin-Join-Hinweis und manuellem `/lottery updatecheck`
- Automatische GitHub Releases bei Push auf `main`, inklusive fertiger JAR und Changelog
- Sprachdateien für Deutsch und Englisch im Ordner `lang`
- Referenzdateien mit Kommentaren werden beim Start unter `reference/` abgelegt

## Commands

- `/lottery`
- `/lottery help`
- `/lottery buy <anzahl>`
- `/lottery free [grund]`
- `/lottery shop`
- `/lottery shop buy <reward>`
- `/lottery gui`
- `/lottery profile [profil]`
- `/lottery reminders`
- `/lottery reminders <draw|win|refund|pot> <on|off>`
- `/lottery winnerwall`
- `/lottery jackpot`
- `/lottery winners`
- `/lottery stats`
- `/lottery nextdraw`
- `/lottery draw`
- `/lottery simulate`
- `/lottery reload`
- `/lottery setjackpot <betrag>`
- `/lottery addjackpot <betrag>`
- `/lottery reset`
- `/lottery info <spieler>`
- `/lottery admin`
- `/lottery admin overview`
- `/lottery admin rounds`
- `/lottery admin transactions`
- `/lottery admin log`
- `/lottery admin payments`
- `/lottery admin stats`
- `/lottery admin taxes`
- `/lottery notifications list [spieler]`
- `/lottery notifications clear <spieler|all>`
- `/lottery payments retry`
- `/lottery backup`
- `/lottery export`
- `/lottery export csv`
- `/lottery export web`
- `/lottery export audit`
- `/lottery audit`
- `/lottery web`
- `/lottery import <datei>`
- `/lottery debug`
- `/lottery doctor`
- `/lottery doctor fix`
- `/lottery setupwizard`
- `/lottery adminstats`
- `/lottery taxreport`
- `/lottery log [seite]`
- `/lottery log player <name> [seite]`
- `/lottery log action <aktion> [seite]`
- `/lottery log date <yyyy-mm-dd> [seite]`
- `/lottery transactions [seite]`
- `/lottery transactions filter <player|type|date|details> <wert> [seite]`
- `/lottery preview <gui|draw|holograms>`
- `/lottery editor`
- `/lottery editor list <gui|stats-gui|personal-stats-gui|language-gui|admin-gui>`
- `/lottery editor set <gui> <item> <slot|material|name|permission|hide_without_permission|lore|actions|add_action|buy_amount> <wert>`
- `/lottery editor updateconfigs`
- `/lottery lotteries list`
- `/lottery lotteries select <profil>`
- `/lottery grantfree <spieler> <grund> [anzahl]`
- `/lottery updateconfigs`
- `/lottery updatecheck`
- `/lottery setup <price|minplayers|drawtime|adddrawtime|multipledraws> <wert>`
- `/lottery setup <cooldown|dailytickets|dailyspend|winners|shares|autobackup|type|fixedprize|profile> <wert>`
- `/lottery season reset [saison-id]`
- `/lottery hologram create <id> countdown`
- `/lottery hologram create <id> statistic <statistik>`
- `/lottery hologram move <id>`
- `/lottery hologram delete <id>`
- `/lottery hologram list [seite]`

## Dateien

- `config.yml`: Regeln, Preise, Steuern, Zeitplan, Teilnahmebedingungen, Shop, Free-Tickets, Webhooks und Backups.
- `gui/gui.yml`: Menüs, Slots, Materialien, Namen, Lore und Klick-Aktionen.
- `lang/de.yml` und `lang/en.yml`: Nachrichten, Broadcasts und Buttons.
- `holograms.yml`: Hologramm-Templates, Anzeigenamen und Positionen.
- `lotteries.yml`: Getrennte Lotterie-Profile.
- `data.yml`: Laufende Runden, Historie, Statistiken, Saisonpunkte und offene Nachrichten.
- `transactions.yml`: Ticketkäufe, Rückerstattungen, Steuern, Auszahlungen und Shopkäufe.
- `admin-log.yml`: Admin-Aktionen und Sicherheitsereignisse.

## Update-Checker

Der Update-Checker fragt standardmäßig den neuesten GitHub Release von `https://api.github.com/repos/Wullverin2/Craftplay-Lotterie/releases/latest` ab. Die Quelle, Cache-Zeit, Startprüfung und Admin-Join-Benachrichtigung können in `config.yml` unter `update-checker` angepasst oder vollständig deaktiviert werden.

## GitHub Releases

Bei jedem Push auf `main` baut GitHub Actions automatisch die aktuelle JAR und veröffentlicht oder aktualisiert den Release zur Versionsnummer aus `pom.xml` und `plugin.yml`, zum Beispiel `v1.0.0`. Der Release enthält die Datei `Craftplay-Lotterie-<version>.jar` und ein Changelog als Release-Text sowie als Markdown-Datei.

Für eine neue öffentliche Version müssen `pom.xml` und `src/main/resources/plugin.yml` dieselbe neue Versionsnummer enthalten. Wenn dieselbe Versionsnummer erneut gepusht wird, wird der bestehende Release dieser Version aktualisiert.

## PlaceholderAPI

Beispiele:

- `%lottery_jackpot%`
- `%lottery_payout_pot%`
- `%lottery_ticket_price%`
- `%lottery_lottery_id%`
- `%lottery_lottery_name%`
- `%lottery_selected_lottery_id%`
- `%lottery_selected_lottery_name%`
- `%lottery_next_draw%`
- `%lottery_time_left%`
- `%lottery_total_tickets%`
- `%lottery_players%`
- `%lottery_player_tickets%`
- `%lottery_player_chance%`
- `%lottery_my_rank_tickets_bought%`
- `%lottery_my_rank_money_spent%`
- `%lottery_my_rank_wins%`
- `%lottery_my_rank_highest_win%`
- `%lottery_my_rank_total_won%`
- `%lottery_my_rank_rounds_played%`
- `%lottery_my_rank_current_tickets%`
- `%lottery_season_points%`
- `%lottery_top_wins_1%`
- `%lottery_top_wins_1_name%`
- `%lottery_top_wins_1_value%`
- `%lottery_top_last_winners_1%`

GUI-Lore kann zusätzlich interne Listen wie `%top_rounds_played%`, `%top_money_spent%`, `%top_wins%`, `%top_highest_win%`, `%top_total_won%`, `%top_current_tickets%` und `%last_winners%` nutzen.

## GUI-Aktionen

In `gui.yml` kann jedes Item eigene Aktionen haben:

- `buy:%buy_amount%`
- `close`
- `open-language`
- `open-personal-stats`
- `open-winner-wall`
- `open-stats`
- `open-admin`
- `open-main`
- `force-draw`
- `reload-plugin`
- `reset-round`
- `language:de`
- `language:en`
- `player:/befehl`
- `console:/befehl`
- `message:&aText`

Spielerköpfe funktionieren über `material: "PLAYER_HEAD"` mit `skull-owner`, über CMI/PlayerHeads-kompatible Materialien oder über `head-database-id`, wenn HeadDatabase installiert ist. PlaceholderAPI-Platzhalter von CMI, Jobs und Quest-Plugins können in GUI, Lang-Dateien und Befehlen genutzt werden.

## Build

```bash
mvn clean package
```

Die fertige Datei liegt danach unter `target/craftplay-lotterie-1.0.0.jar`.
