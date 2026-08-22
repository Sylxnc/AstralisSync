# AstralisSync – In-Game Testplan

Vollständige Abnahme aller Features. Jeder Punkt mit Checkbox – erst abgehakt, wenn das erwartete Ergebnis exakt eingetroffen ist.

---

## 0. Voraussetzungen

- [ ] Zwei Paper-1.21-Server aufgesetzt: **A** (`server-id: test-a`) und **B** (`server-id: test-b`), beide mit demselben Plugin-Jar
- [ ] Beide zeigen auf dieselbe MySQL-Datenbank und dieselbe Redis-Instanz
- [ ] `keepInventory` in der Welt von Server A auf `false` (wichtig für den Death-Snapshot-Test)
- [ ] Test-Account mit OP-Rechten, idealerweise zusätzlich ein Zweit-Account für InvSee/Dual-Login
- [ ] Optional: PlaceholderAPI installiert (für Abschnitt 12)

**Nach dem ersten Start prüfen:**

- [ ] Log enthält `Connected to MySQL.` und `Connected to Redis.` auf beiden Servern
- [ ] `/astralissync status` zeigt auf beiden: MySQL verbunden, Redis verbunden, korrekte `server-id`
- [ ] In MySQL existieren die Tabellen: `player_data`, `player_meta`, `player_snapshots`, `advancement_data`, `statistics_data`

---

## 1. Basis-Sync (A → B)

| # | Schritt | Erwartet |
|---|---|---|
| 1.1 | Auf A: Inventar befüllen – Items ins Hauptinventar, volle Rüstung, Item in die Offhand, Hotbar-Slot 3 auswählen | – |
| 1.2 | Auf A: XP farmen (mind. Level 10), Hunger bewusst auf ~6 senken, einen Effekt geben (`/effect give @s speed 300`), GameMode Survival sicherstellen | – |
| 1.3 | `/astralissync save` | Bestätigungsmeldung, keine Fehler im Log |
| 1.4 | Ausloggen auf A, auf B joinen | Join-Titel erscheint |
| 1.5 | Inventar vergleichen | Identische Items, Rüstung sitzt, Offhand stimmt, **Hotbar-Slot ist 3** |
| 1.6 | XP-Leiste, Herzen, Hungerleiste prüfen | Exakt wie beim Logout auf A |
| 1.7 | Aktive Effekte prüfen (`/effect` oder Partikel) | Speed läuft weiter |
| 1.8 | Richtung B → A wiederholen | Gleiches Ergebnis in beiden Richtungen |

- [ ] 1.1–1.8 bestanden

---

## 2. Enderchest & Row-Upgrades

| # | Schritt | Erwartet |
|---|---|---|
| 2.1 | Auf A: Enderchest öffnen, Items in alle drei Standardreihen legen | – |
| 2.2 | Auf B: Enderchest öffnen | Inhalt identisch |
| 2.3 | `/astralissync ec set <eigenerName> 4` | Bestätigung, Rows = 4 |
| 2.4 | Enderchest erneut öffnen | **Vier Reihen sichtbar**, Items aus Zeile 1–3 unberührt |
| 2.5 | Items ausschließlich in Reihe 4 legen, Chest schließen, `/astralissync save`, Serverwechsel | Reihe 4 inkl. Inhalt auf dem anderen Server vorhanden |
| 2.6 | `/astralissync ec set <eigenerName> 6`, dann `/astralissync ec upgrade` | Erst 6 Rows, dann Meldung „bereits auf dem Maximum"; Upgrade schlägt fehl (Rückgabe −1) |
| 2.7 | Items in Reihe 5 und 6 legen, Sync-Test | Alles kommt an |

- [ ] 2.1–2.7 bestanden

---

## 3. Snapshots

| # | Schritt | Erwartet |
|---|---|---|
| 3.1 | `/snapshots` | GUI öffnet sich, leer oder mit bisherigen Einträgen, Navigation unten |
| 3.2 | `/snapshots save` | Meldung „Snapshot gespeichert"; neuer Eintrag mit Papier-Icon, Grund `manual` |
| 3.3 | Mehrere Items aus dem Inventar löschen, dann `/snapshots restore <id-aus-3.2>` | Inventar exakt wie bei 3.2; zusätzlich entstand ein neuer Snapshot mit Grund `pre-restore` |
| 3.4 | Tot sein lassen: ohne KeepInventory in Kaktus/Cactus oder Void laufen | Nach dem Respawn: `/snapshots` zeigt neuen Eintrag mit Skelettkopf, Grund `death`, Zustand = Sekunden vor dem Tod |
| 3.5 | Den Death-Snapshot wiederherstellen | Inventar/Hunger/Position vom Todeszeitpunkt sind zurück |
| 3.6 | Ausloggen und direkt wieder einloggen | Snapshot mit Grund `quit` wurde erstellt |
| 3.7 | Temporär `snapshots.max-per-player: 3` setzen, viermal speichern | Nur die drei neuesten Snapshots bleiben, älteste wurden entfernt |
| 3.8 | GUI-Paging testen: >36 Snapshots eines Accounts erzeugen (Skript oder mehrfaches save/restore) | Pfeil-Buttons funktionieren, Seitenzahl stimmt |

- [ ] 3.1–3.8 bestanden

---

## 4. InvSee

| # | Schritt | Erwartet |
|---|---|---|
| 4.1 | Zweit-Account auf **demselben** Server einloggen, Viewer macht `/invsee <account2>` | Titel zeigt `(Live)` grün; Inventar des Ziels sichtbar |
| 4.2 | Im Live-Fenster ein Item in ein freies Ziel-Inventarfeld legen | Nach ca. 2 Sekunden hat der Ziel-Spieler das Item real im Inventar |
| 4.3 | Ziel-Spieler wechselt auf Server B, Viewer bleibt auf A: `/invsee` erneut | Titel zeigt `(Remote)` grau; Klicks werden komplett blockiert (nichts verschiebbar) |
| 4.4 | Während der Remote-Ansicht: Ziel wechselt auf B ein Item | Viewer schließt und öffnet erneut → neues Item sichtbar (Cache wurde per REQ_SAVE aktualisiert) |
| 4.5 | `/invsee <offline-bekannter-spieler>` | Ansicht aus der Datenbank, kein Fehler |
| 4.6 | `/invsee unbekanntername123` | Meldung „Keine Daten gefunden", kein Crash |

- [ ] 4.1–4.6 bestanden

---

## 5. Gutscheine

| # | Schritt | Erwartet |
|---|---|---|
| 5.1 | `/astralissync voucher ec-row` | Ender-Eye mit Glitzer-Effekt im Inventar, Lore sichtbar |
| 5.2 | **Rechtsklick** mit dem Voucher | Meldung „Enderchest erweitert auf X Reihen", Item wird verbraucht, EC hat sofort eine Reihe mehr |
| 5.3 | Nächsten Voucher mit **Linksklick** einlösen | Gleiches Ergebnis |
| 5.4 | Bis Maximum upgraden, letzten Voucher klicken | Meldung „bereits auf dem Maximum", **Item bleibt im Inventar** |
| 5.5 | Voucher ins Inventar legen, `/astralissync save`, Serverwechsel | Voucher kommt mit (PDC überlebt den Sync) |
| 5.6 | Dort wieder einlösen | Funktioniert |

- [ ] 5.1–5.6 bestanden

---

## 6. Shop

| # | Schritt | Erwartet |
|---|---|---|
| 6.1 | 16 Diamanden besorgen, `/vouchershop` | GUI mit ec-row (Slot 11) und ec-max (Slot 15), Kosten in der Lore |
| 6.2 | ec-row kaufen | 16 Diamanden entfernt, Ender-Eye-Voucher im Inventar |
| 6.3 | Nur 5 Diamanden haben, erneut kaufen | Meldung „nicht genug", es wird nichts abgezogen und nichts gegeben |
| 6.4 | ec-max mit XP-Kosten kaufen (z.B. Level 30 gefordert) | Level sinkt um den Preis, Nether-Star-Voucher erhalten |
| 6.5 | Shop schließen und erneut öffnen | Zustand konsistent, keine Ghost-Items |

- [ ] 6.1–6.5 bestanden

---

## 7. Dual-Login-Lock

| # | Schritt | Erwartet |
|---|---|---|
| 7.1 | Account auf A einloggen und online lassen | – |
| 7.2 | Denselben Account auf B joinen lassen | Kick mit „Daten werden gerade auf einem anderen Server gespeichert" |
| 7.3 | Falls Webhook konfiguriert (Abschnitt 9): Embed „Login blockiert" prüfen | Vorhanden |
| 7.4 | Auf A ausloggen, ~2 Sekunden warten, auf B joinen | Join funktioniert normal, Daten vollständig |
| 7.5 | Sofortiges Rejoin (unter 1 Sekunde) auf B versuchen | Ggf. einmaliger Kick mit Warte-Hinweis, danach erfolgreicher Join – **nie doppeltes Laden** |

- [ ] 7.1–7.5 bestanden

---

## 8. Integrität / Checksummen

| # | Schritt | Erwartet |
|---|---|---|
| 8.1 | Mit Spieler offline: `UPDATE player_data SET data = CONCAT(data,'x') WHERE uuid='<uuid>';` | – |
| 8.2 | Join auf irgendeinem Server | Kein Crash; Spieler startet mit leerem/frischem Stand |
| 8.3 | Log prüfen | SEVERE-Eintrag mit Checksummen-Fehler und UUID |
| 8.4 | MySQL: `SELECT COUNT(*) FROM corrupted_player_data WHERE uuid='<uuid>';` | ≥ 1 – die defekte Zeile wurde gesichert |
| 8.5 | `integrity.checksums: false` setzen, neu speichern, Join testen | Normaler Betrieb ohne Prüfungsfehler |

- [ ] 8.1–8.5 bestanden

---

## 9. Discord-Webhooks

| # | Schritt | Erwartet |
|---|---|---|
| 9.1 | Gültige Webhook-URL in `discord-webhook.url` eintragen, `/astralissync reload` | – |
| 9.2 | `/snapshots restore <id>` ausführen | Embed „Snapshot wiederhergestellt" erscheint im Kanal |
| 9.3 | `/astralissync purge <testspieler>` | Embed „Spielerdaten gelöscht" |
| 9.4 | Dual-Login-Szenario aus Abschnitt 7 erneut triggern | Embed „Login blockiert" |
| 9.5 | URL entfernen, erneut Restore | Kein Fehler, kein Versand |

- [ ] 9.1–9.5 bestanden

---

## 10. Export / Import

| # | Schritt | Erwartet |
|---|---|---|
| 10.1 | Testinventar aufbauen, `/syncexport export <eigenerName>` | Datei in `plugins/AstralisSync/exports/<name>-<zeitstempel>.json` |
| 10.2 | JSON ansehen | Felder `inventory.storage` (Base64), `enderChest`, `xp`, `health` usw. gefüllt |
| 10.3 | Inventar komplett leeren, dann `/syncexport import <dateiname>` | Inventar, EC-Stand, XP und Gesundheit entsprechen dem Export |
| 10.4 | Tab-Complete beim Import zeigt nur Dateien aus dem exports-Ordner | Ja; `../`-Pfade werden abgelehnt |

- [ ] 10.1–10.4 bestanden

---

## 11. Advancements & Statistiken

| # | Schritt | Erweitert |
|---|---|---|
| 11.1 | Auf A ein einfaches Advancement freispielen (z.B. „Steinzeit") | – |
| 11.2 | `/astralissync save`, Wechsel auf B | Advancement ist auch dort freigespielt |
| 11.3 | Statistik merken: „Blöcke abgebaut" (`/statistics` oder F3) | – |
| 11.4 | Auf B einige Blöcke abbauen, speichern, zurück auf A | Zähler führt inklusive der Blöcke von B weiter |

- [ ] 11.1–11.4 bestanden

---

## 12. PlaceholderAPI

| # | Schritt | Erwartet |
|---|---|---|
| 12.1 | PAPI installieren, AstralisSync-Server neu starten | Log: „PlaceholderAPI expansion registered" |
| 12.2 | `%astralissync_server%` über ein Chat-/Scoreboard-Plugin anzeigen | Zeigt die konfigurierte `server-id` |
| 12.3 | `%astralissync_ecrows%` anzeigen | Zeigt aktuelle eigene Reihen-Zahl |
| 12.4 | `%astralissync_ecrows_<offline-name>%` anzeigen | Liefert Wert aus der Datenbank, kein Timeout |

- [ ] 12.1–12.4 bestanden

---

## 13. Grenzfälle & Stabilität

| # | Schritt | Erwartet |
|---|---|---|
| 13.1 | Spieler online lassen, Server A stoppen | Finaler Save im Log; nach Neustart auf B ist der Zustand aktuell |
| 13.2 | Redis stoppen, dann Joinversuch | Join wird abgelehnt (fail-closed); laufende Spieler spielen ohne Fehler weiter; nach Redis-Restart funktioniert alles wieder |
| 13.3 | MySQL stoppen, Spieler online lassen | Autosave loggt Fehler, Server läuft weiter; Spieler fliegt nicht raus |
| 13.4 | Beide Server parallel starten | Keine Table-Creation-Konflikte, beide melden Connected |
| 13.5 | 30 Minuten AFK auf einem Server stehen lassen | Lock-Renewal verhindert Lock-Ablauf; danach normaler Serverwechsel möglich |

- [ ] 13.1–13.5 bestanden

---

## Protokoll

| Datum | Build | Server A | Server B | Getestet von | Ergebnis |
|---|---|---|---|---|---|
|  |  |  |  |  |  |

**Release erst freigeben, wenn alle Abschnitte abgehakt sind.**
