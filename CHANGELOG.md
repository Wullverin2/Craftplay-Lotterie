# Changelog

## 1.0.1

- Dauerhaftes Änderungsjournal mit Prüfsummen, automatischer Wiederherstellung und gebündelten YAML-Checkpoints im Hintergrund.
- Atomarer Dateiaustausch und vollständiges Leeren der Schreibwarteschlange bei Reload und Serverstopp.
- Inkrementelle SQLite-/MySQL-Abgleiche statt vollständiger Tabellenlöschung; neuere lokale Daten werden beim Start erhalten.
- Gemeinsame Ranglisten und Platzierungen für Java-/Bedrock-GUIs, PlaceholderAPI, Hologramme und Website.
- Gemeinsame Platzhalter pro Menüaufbau, zwischengespeicherte Web-Gesamtsummen und vorbereitete HTTP-Antworten.
- Asynchrone Backups, Audit-ZIPs und Exporte mit unveränderlichem Datenstand und allen sprachabhängigen GUI-Dateien.
- Gebündelte Protokollschreibvorgänge bei Rückerstattungen und Auszahlungen sowie kleinere Offline-Benachrichtigungen.
- Direkter Ticket-Gesamtzähler, unveränderter Fairness-Snapshot ohne quadratische String-Verkettung und gezielte Hologramm-Updates.
- Wiederverwendung von PlaceholderAPI-Methoden und HeadDatabase-Köpfen, schnellere Menüerkennung und Bereinigung abgelaufener Sitzungsdaten.
- Regressionstests für Journal-Wiederherstellung, konkurrierende Checkpoints, Datenbank-Rollbacks, Caches und Fairness-Kompatibilität.
