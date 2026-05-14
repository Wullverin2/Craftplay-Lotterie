# Craftplay Lotterie

Ein voll ausgestattetes Lotterie-Plugin fuer Paper/Spigot mit Vault, GUI, PlaceholderAPI und Webhook-Support.

## Features

- taegliche automatische Ziehung mit Zeitzone
- Ticketkauf per Befehl und GUI
- Jackpot mit optionalem Steuerabzug
- konfigurierbarer Zusatzbetrag im Topf
- maximale Tickets pro Kauf und pro Spieler
- Kauf-Cooldown sowie Tageslimits fuer Tickets und Ausgaben
- Mindestanzahl an Spielern vor der Ziehung
- Rueckzahlung der Tickets, wenn wegen zu wenigen Teilnehmern nicht gezogen wird
- kein Topf-Uebertrag, wenn keine Ziehung stattfindet
- Gewinnerhistorie mit gespeicherten Ticketzahlen
- Broadcast beim Ticketkauf und beim Gewinn
- persistente Spielerstatistiken in `data.yml`
- Statistik-Unterseite im GUI mit Top-10-Listen
- TextDisplay-Hologramm bis zur naechsten Ziehung
- Statistik-Hologramme mit eigenen Templates pro Statistikart
- anklickbarer Kaufen-Button in der Ziehungsankuendigung
- Join-Reminder fuer Spieler
- Titel- und Sound-Effekte beim Gewinn
- PlaceholderAPI-Integration
- Discord/Webhook-Benachrichtigungen
- Discord/Webhook-Embeds mit Gewinnerdaten
- optionale Webhook-Events fuer Ticketkauf und fehlgeschlagene Ziehungen
- Admin-Befehle fuer Steuerung und Debugging
- Admin-GUI mit Status, Doctor, Simulation, Reload, Reset, Force-Draw und Offline-Nachrichten
- Admin-Log in `admin-log.yml`
- Config-Migration mit Backup unter `migrations`
- optionale SQL-Speicherung per SQLite oder MySQL fuer Snapshots und strukturierte Statistik-Tabellen
- Retry-System fuer fehlgeschlagene Auszahlungen und Rueckerstattungen
- Backup, Auto-Backup nach Ziehungen, Export, Import, Debug und Setup-Befehle
- mehrere Ziehzeiten pro Tag optional ueber `settings.draw-schedule.multiple-draws-enabled`
- optionale Wochentags-Regeln fuer Ziehungen
- optionale Gewinnbefehle ueber `rewards.commands-on-win`
- optionale gewichtete Gewinnpakete ueber `rewards.packages`
- mehrere Gewinner pro Ziehung mit gewichteter Gewinnverteilung
- Saison-Statistiken mit Reset-Befehl
- Steuerstatistik fuer eingesammelte Gebuehren
- Jackpot-Boost-Events per Multiplikator
- Kaufbestaetigung fuer grosse Ticketkaeufe
- Teilnahmebedingungen per Permission, Welt und Mindestspielzeit
- animierte Ziehung mit Countdown
- Testziehung ohne Auszahlung oder Datenveraenderung

## Commands

- `/lottery`
- `/lottery help`
- `/lottery buy <anzahl>`
- `/lottery gui`
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
- `/lottery notifications list [spieler]`
- `/lottery notifications clear <spieler|all>`
- `/lottery payments retry`
- `/lottery backup`
- `/lottery export`
- `/lottery import <datei>`
- `/lottery debug`
- `/lottery doctor`
- `/lottery log [seite]`
- `/lottery setup <price|minplayers|drawtime|adddrawtime|multipledraws> <wert>`
- `/lottery setup <cooldown|dailytickets|dailyspend|winners|shares|autobackup> <wert>`
- `/lottery season reset [saison-id]`
- `/lottery hologram create <id> countdown`
- `/lottery hologram create <id> statistic <statistik>`
- `/lottery hologram move <id>`
- `/lottery hologram delete <id>`
- `/lottery hologram list [seite]`

## PlaceholderAPI

- `%lottery_jackpot%`
- `%lottery_ticket_price%`
- `%lottery_draw_time%`
- `%lottery_next_draw%`
- `%lottery_time_left%`
- `%lottery_total_tickets%`
- `%lottery_players%`
- `%lottery_player_tickets%`
- `%lottery_player_chance%`
- `%lottery_draw_times%`
- `%lottery_min_players%`
- `%lottery_pending_notifications%`
- `%lottery_pending_payments%`
- `%lottery_tax_collected_total%`
- `%lottery_season_id%`
- `%lottery_winner_count%`
- `%lottery_boost_multiplier%`
- `%lottery_player_tickets_bought%`
- `%lottery_player_money_spent%`
- `%lottery_player_wins%`
- `%lottery_player_total_won%`
- `%lottery_player_stats_highest_win%`
- `%lottery_player_stats_rounds_played%`
- `%lottery_player_stats_profit%`
- `%lottery_season_tickets_bought%`
- `%lottery_season_money_spent%`
- `%lottery_season_wins%`
- `%lottery_season_total_won%`
- `%lottery_season_profit%`
- `%lottery_top_wins_1%`
- `%lottery_top_wins_1_name%`
- `%lottery_top_wins_1_value%`

## Konfiguration

- `config.yml` enthaelt Regeln, Preise, Zusatzbetrag, Ziehungszeiten, Webhook, Effekte, Gewinnbefehle und den optionalen Kaufen-Button fuer Ziehungsankuendigungen.
- `lang/de.yml` und `lang/en.yml` enthalten alle Chat-Nachrichten und Broadcasts.
- `gui/gui.yml` enthaelt Titel, Slots, Materialien, Namen, Lore und Klick-Aktionen des GUI.
- `holograms.yml` enthaelt alle Hologramm-Einstellungen und Positionen.
- `admin-log.yml` wird automatisch erstellt und enthaelt Kaeufe, Ziehungen, Rueckzahlungen und Admin-Aktionen.

## Speicher

Standard ist YAML. Optional kann `storage.type` auf `sqlite` oder `mysql` gestellt werden. Das Plugin speichert dann die aktuellen Daten- und Log-Snapshots zusaetzlich in der Datenbank und laedt sie beim Start daraus zurueck. Zusaetzlich werden Runde, Spielerstatistiken, Gewinnerhistorie und offene Zahlungen in separaten Tabellen gespiegelt.

```yaml
storage:
  type: "sqlite"
```

## Betrieb

- `/lottery backup` erstellt ein ZIP-Backup unter `plugins/CraftplayLotterie/backups`.
- Bei aktivem `backups.auto-after-draw.enabled` wird nach jeder Ziehung automatisch ein ZIP-Backup erstellt.
- `/lottery export` exportiert `data.yml` nach `exports`.
- `/lottery import <datei>` importiert nur Dateien aus dem `exports`-Ordner.
- `/lottery debug` zeigt Storage, Hooks, naechste Ziehung, offene Nachrichten und offene Zahlungen.
- `/lottery payments retry` versucht fehlgeschlagene Auszahlungen/Rueckerstattungen erneut.
- `/lottery simulate` zeigt einen moeglichen Gewinner der aktuellen Runde, ohne etwas auszuzahlen oder die Runde zu beenden.

## GUI-Aktionen

In `gui.yml` kann jedes Item eigene Aktionen haben:

- `buy:%buy_amount%`
- `close`
- `player:/befehl`
- `console:/befehl`
- `message:&aText`
- `open-language`
- `open-personal-stats`
- `open-stats`
- `open-admin`
- `language:de`
- `language:en`

Kaufbuttons nutzen `buy-amount`, Dekoitems haben einfach keine Aktionen. Spielerkoepfe funktionieren ueber `material: "PLAYER_HEAD"` und `skull-owner: "%player_name%"`. PlaceholderAPI-Platzhalter von CMI, Jobs und Quest-Plugins koennen in Namen, Lore, Nachrichten und Befehlen genutzt werden, wenn die jeweilige PlaceholderAPI-Erweiterung installiert ist.

## Hologramme

Hologramme werden in `holograms.yml` gespeichert. Es gibt zwei Typen:

- `countdown` zeigt die Zeit bis zur naechsten Ziehung.
- `statistic` zeigt eine Top-10-Statistik.

Befehle:

- `/lottery hologram create <id> countdown`
- `/lottery hologram create <id> statistic <statistik>`
- `/lottery hologram move <id>`
- `/lottery hologram delete <id>`
- `/lottery hologram list [seite]`

Statistikwerte fuer Hologramme: `rounds_played`, `tickets_bought`, `money_spent`, `wins`, `highest_win`, `total_won`, `current_tickets`.
In `holograms.yml` kann jede Statistikart unter `templates.statistics.<statistik>` eigene Linien und einen eigenen Anzeigenamen erhalten.

## Statistik

Das Plugin speichert pro Spieler dauerhaft:

- gekaufte Tickets insgesamt
- Geld fuer Tickets insgesamt
- gespielte Runden
- Anzahl Gewinne
- hoechster Einzelgewinn
- alle Gewinne zusammen
- letzte Kaeufe und letzte Gewinne

Die Statistik-Seite im GUI nutzt Lore-Platzhalter wie `%top_rounds_played%`, `%top_money_spent%`, `%top_wins%`, `%top_highest_win%`, `%top_total_won%` und `%top_current_tickets%`.

## Build

```bash
mvn clean package
```

Die fertige Datei liegt danach unter `target/craftplay-lotterie-1.0.0.jar`.
