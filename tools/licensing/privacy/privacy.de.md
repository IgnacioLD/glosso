# Datenschutz und Analytik - Glosso

Zusammenfassung, welche Daten das Produkt verarbeitet und wie das in Google Play
deklariert wird. Keine Rechtsberatung.

## Audio

- Audio wird **nur** zum Erkennen und Bewerten der Aussprache an den Server
  gesendet.
- Es wird im Speicher verarbeitet und **am Ende der Anfrage verworfen**. Es wird
  nicht gespeichert, protokolliert oder wiederverwendet.
- Im Offline-Modus (spätere Phase) verlässt das Audio das Gerät nicht.

## First-Party-Analytik (`/v1/events`)

Wir erfassen Produktereignisse in unserer eigenen Datenbank (Postgres,
`glosso.analytics_event`). **Keine Weitergabe an Dritte.**

| Datum | Beispiel | Zweck |
|---|---|---|
| Zufällige Installations-ID | Zufällige UUID, beim ersten Start erzeugt | Eindeutige Geräte zählen |
| Sitzungs-ID | Zufällige UUID | Ereignisse einer Sitzung gruppieren |
| App-Version / Plattform | `1.0.0`, `android` | Kompatibilität |
| Geübte Sprache | `en_GB` | Verbesserung des Kurses |
| Ereignisname und -eigenschaften | `score_attempt`, `score`, `level` | Nutzung und Schwierigkeit analysieren |

**Wir erfassen nicht**: Advertising ID, IMEI, MAC, `ANDROID_ID` als stabile
Kennung, Gerätemodell oder Marke, genauen Standort, Kontakte, SMS, freien
Nutzertext, Fehlermeldungen oder Audio.

Der Server **speichert die IP nicht** in der Ereignistabelle. Serverereignisse
(`score_attempt`, `503`, Fehler) werden genauso erfasst, ohne Audio.

## Konto: Weiter mit Google (optional)

Es nimmt das Konto auf ein neues Handy mit. Es ist
optional; die App funktioniert ohne es genauso.

- **Weiter mit Google**: Die Person wählt ein Google-Konto auf dem Handy
  (Android Credential Manager). Angefragt wird nur die Identität: Der Server
  prüft das ID-Token und speichert **nur** die undurchsichtige Kennung, die
  Google dem Konto gibt (`sub`). E-Mail, Name, Foto und das Token werden
  **nicht** gespeichert, und es wird kein Zugriff auf Gmail, Drive oder andere
  Dienste angefragt. Die E-Mail wird nur auf dem Handy selbst angezeigt.

## Aufbewahrung

- Konfigurierbar mit `GLOSSO_ANALYTICS_RETENTION_DAYS` (Standard **180 Tage**).
- Löschung beim Serverstart.

## Sicherheit

- **TLS erforderlich**: die App spricht nur über HTTPS mit dem Endpunkt.
- `/metrics`, `/stats`, `/admin/events` und `/v1/events/summary` sind mit einem
  Token geschützt (`GLOSSO_ADMIN_TOKEN`); ohne Token bleiben sie geschlossen.
- Größenlimit für Ereignisse und Audio; Sicherheitsheader (HSTS,
  `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`).

## Google-Play-Deklaration (Datensicherheit)

- **Erfasste Daten / App-Aktivität**: "Other actions" -> ja
  (Nutzungsereignisse).
- **Erfasste Daten / Personenbezogene Daten / Nutzer-IDs**: ja, nur wenn die
  Person mit Google weitermacht (optional): die Kennung des Google-Kontos.
- **Erfasste Daten / Gerät oder andere IDs**: "Device or other IDs" -> ja
  (zufällige Installations-ID).
- **Erfasste Daten / App-Informationen und -Leistung**: "Crash logs" und
  "Diagnostics" -> ja, ab App 2.5.4 (Art des Fehlers, Stelle im Code und
  technischer Aufrufstapel ohne Meldungen oder Benutzerdaten;
  Bewertungsgeschwindigkeit, Android-Version, Telefon oder Tablet). Nicht
  weitergegeben, optional, für Analytik und App-Funktionalität.
- **Weitergabe an Dritte**: nein.
- **Bei Übertragung verschlüsselt**: ja.
- **Datenlöschung**: Support-Kanal anbieten und, falls zutreffend, einen
  In-App-Button zum Zurücksetzen der ID und Löschen der zugehörigen Daten.

## Deine Wahl

- Nutzungsstatistiken sind standardmäßig an, und das Onboarding sagt es. Sie
  lassen sich jederzeit unter Einstellungen > Nutzungsstatistiken abschalten;
  die App funktioniert ohne sie genauso, und der Server erfasst dann auch das
  Bewertungsereignis dieses Geräts nicht mehr.
- „Meine Nutzungsdaten löschen" an derselben Stelle löscht alle Ereignisse
  dieses Geräts auf unserem Server.
- Die Ereignisse der App: App geöffnet, Sitzungsdauer, Onboarding begonnen und
  beendet, Lektion begonnen, beendet oder verlassen, Übung beendet (Art,
  10-Punkte-Bereich der Bewertung, gemeistert oder nicht), auf dem Telefon
  bewerteter Ausspracheversuch (Bewertungsbereich, gemeistert oder nicht, IDs
  der Fehlerregeln) und Mikrofon verweigert.

Ab App 2.5.4 sendet die App außerdem:

- Beim Öffnen der App der Kontext: Android-Version, Telefon oder Tablet,
  Dunkelmodus an oder aus, ob das optionale Modell auf dem Gerät installiert
  ist, Sprache der Oberfläche und gewählter Akzent. Nie das Gerätemodell, die
  Marke oder eine Hardware-Kennung.
- Die Bewertungsgeschwindigkeit (Millisekunden) und ob auf dem Telefon
  bewertet wurde.
- Angesehene Bildschirme (aus einer festen Liste), erreichte Lektionsschritte,
  abgespieltes Audio (welche Art von Audio, normal oder langsam, und das
  Abspielen deiner eigenen Aufnahme, nie die Aufnahme), Ergebnisse von „Du
  bist dran“ und der Wiederholung (gewusst, teilweise, nicht dieser Satz,
  aufgedeckt), geöffnete Geschichten und wie viel gelesen wurde (Prozent)
  sowie die Länge der täglichen Serie.
- Geänderte Einstellungen (welche und ihr neuer Wert: Lernsprache, Akzent,
  Sprache der Oberfläche, Lautschrift, Übersetzungen, tägliche Erinnerung,
  Downloads nur über WLAN).
- Downloads von Lektionen oder des Modells (begonnen, abgeschlossen oder
  fehlgeschlagen, Größe in MB, kurzer Fehlercode) und Modell gelöscht.
- Sichtbare Fehler nach Art (Bewertung fehlgeschlagen, offline, nicht
  bewertet, Mikrofon, Inhalt, Audio) und Absturzberichte: die Art des Fehlers,
  die Stelle im Code der App und der technische Aufrufstapel (Klasse, Methode,
  Datei und Zeile), beim nächsten Start bei fatalen Abstürzen gesendet;
  nie die Fehlermeldung, der Inhalt oder etwas, das du eingibst.
- Benachrichtigungsberechtigung erteilt oder nicht und Ergebnis der Anmeldung
  mit Google (erfolgreich, abgebrochen, kein Konto, fehlgeschlagen).

Alles nach denselben Regeln: nur Ja/Nein-Werte, begrenzte Zahlen und IDs, nie
Freitext oder Audio; 180 Tage aufbewahrt, und „Meine Nutzungsdaten löschen"
löscht alles, Absturzberichte eingeschlossen. Der Server verwirft jedes
andere Ereignis.
