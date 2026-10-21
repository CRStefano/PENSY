<p align="center">
  <img src="docs/logo.png" width="112" alt="PENSY">
</p>

<h1 align="center">PENSY</h1>

<p align="center">
  Le carte di tutti i giorni, in ordine. Scadenze, salute e conti sul telefono, <b>senza internet</b>.<br>
  <a href="#english">English below</a>
</p>

---

PENSY trasforma le carte di tutti i giorni in testo, scadenze e conti chiari e le tiene in ordine in
fascicoli. Fotografi una bolletta, un referto, il bollo, un ticket (o carichi il PDF che ti hanno mandato
per email): PENSY legge il testo, capisce di cosa si tratta, lo mette nel fascicolo giusto
(Casa › Acqua, Salute › Cuore, Auto…), salva il QR o il codice a barre e, se c'è da pagare o da fare
qualcosa, ti avvisa per tempo.

App Android in italiano e inglese. Gratuita, senza pubblicità, senza account.

## Cosa fa

- **Documenti** – foto o file (PDF, immagini, anche più pagine). Lettura del testo sul telefono, tipo
  riconosciuto da solo (bollette, pagoPA, bollo, multe, F24, ticket, referti, ricette, buste paga,
  garanzie, carta d'identità, patente, passaporto…), smistamento nei fascicoli, ricerca nel testo.
- **Scadenze** – calendario, ricorrenze, avvisi a più tappe, QR e codici a barre ridisegnati da mostrare
  allo sportello, ricevute allegate.
- **Bilancio** – dalla busta paga «ti restano X € questo mese», spese di tutti i giorni in tre tocchi,
  spese fisse, andamento di mesi e anni, spese per il 730, CSV per Excel.
- **Risparmi e investimenti** – salvadanai con obiettivo, investimenti (ETF, azioni, fondi, fondo
  pensione, conto deposito…) con piano di accumulo, guadagno e proiezione. I valori li inserisci tu:
  nessun prezzo scaricato da internet.
- **Salute** – patologie, allergie, interventi, vaccini e richiami; farmaci ogni giorno, alcuni giorni
  della settimana, ogni mese o una volta sola, con o senza orario; scorte a scatole con promemoria per
  ordinare; visite da prenotare e prenotate; misurazioni (pressione, glicemia, peso, altezza…) con
  grafici; PDF «Mostra al medico» e scheda di emergenza.
- **Famiglia** – un profilo per ogni persona (figli, un genitore) e per gli animali, ognuno con i suoi
  fascicoli, scadenze e salute.
- **E poi** – widget, blocco con impronta o PIN, manuale d'uso nell'app, primo avvio guidato.

Il [manuale completo](docs/manuale.md) e le [domande frequenti](docs/domande.md) sono anche qui, in
una pagina sola.

## Privacy: tutto resta sul telefono

- PENSY **non ha il permesso di accedere a internet**: è Android stesso a impedirle di collegarsi.
  Niente server, niente account, niente statistiche. Anche la lettura del testo avviene sul telefono.
- I dati stanno nel telefono e, cifrati, nella cartella **`PENSY_DATA`** che scegli tu.
  Per cambiare telefono basta copiare quella cartella (o il file unico creato con «Esporta») e,
  sul nuovo, toccare «Recupera i miei dati».
- Senza la tua password i dati non si possono leggere né recuperare.

Permessi usati: notifiche, riavvio (per rimettere gli avvisi dopo l'accensione), «Sveglie e promemoria»
(per le medicine all'ora esatta) e impronta (per «Blocca PENSY», tramite la richiesta di sistema:
PENSY non vede l'impronta).

## Scaricare e installare

1. Apri la pagina **[Releases](../../releases)** e scarica l'ultimo `PENSY-x.y.apk` dal telefono.
2. Aprilo. La prima volta Android chiede di permettere l'installazione di app da quella sorgente
   (il browser o l'app dei file): consenti e torna indietro.
3. Le nuove versioni si installano sopra la precedente: i dati restano.

Serve Android 8.0 o più recente. Accanto a ogni APK c'è il file `.sha256` per chi vuole controllarlo.

**iPhone**: non ancora. È il prossimo passo, separato dall'app Android.

## Costruire l'app dal codice

**Android Studio** – apri la cartella `android/` e premi Run. Il lettore di testo (Tesseract) è già
compilato nel progetto: non serve installare altro.

**Da terminale, con Gradle**

```sh
cd android
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # firmata con la tua chiave, vedi «Firma» sotto
```

**Senza Gradle** – `tools/build-apk.sh` costruisce l'APK con aapt2, kotlinc, dx, zipalign e apksigner
(le variabili da impostare sono scritte in cima allo script).

**Test del nucleo** (serve `kotlinc` e un JDK 17):

```sh
KOTLINC=/percorso/kotlinc/bin/kotlinc ./tools/test-core.sh
```

Ogni modifica caricata su GitHub fa partire da sola i test e la costruzione dell'app
(`.github/workflows/ci.yml`). Le versioni da pubblicare si costruiscono e si firmano con
`.github/workflows/release.yml`: istruzioni in [docs/PUBBLICARE.md](docs/PUBBLICARE.md).

## Struttura

```
core/          il nucleo in Kotlin puro, senza Android: modello, importi, ricorrenze, promemoria,
               bilancio, investimenti, lettore dei documenti, smistamento, cifratura, salute
  test/        test eseguibili da terminale, anche sull'uscita reale dell'OCR
android/       l'app (Kotlin + framework Android, nessuna libreria AndroidX)
  app/src/main/java/        schermate, database, notifiche, widget, PDF
  app/src/main/jniLibs/     il lettore di testo compilato per i telefoni (arm64, armv7, x86_64)
  app/src/main/assets/      i modelli italiano e inglese di Tesseract
  app/src/main/res/raw/     manuale, guida e LEGGIMI di PENSY_DATA (raw-en/ in inglese)
tools/
  test-core.sh   test del nucleo
  build-apk.sh   costruisce l'APK senza Gradle
  check-indy.py  ferma la build senza Gradle se il codice può arrivare a una funzione che su
                 Android si chiuderebbe (invokedynamic)
  build-ocr.sh   ricompila il lettore di testo (Tesseract + Leptonica) per Android
  ocr/           il ponte C++ tra Tesseract e l'app
docs/          manuale, domande frequenti, come pubblicare una versione
```

## Principi

- **Offline, sempre.** Nessun permesso di rete. Quello che serve (lettura del testo, codici, PDF,
  grafici) è dentro l'app.
- **Testo prima delle foto.** Di base si conservano il testo e il contenuto esatto dei codici; la foto
  solo se la scegli.
- **Quasi zero dipendenze.** Interfaccia scritta con il framework Android; solo ZXing (codici) e
  Tesseract (testo), entrambi offline.
- **Il nucleo è separato.** Tutta la logica sta in `core/`, testabile senza telefono.
- **Dati semplici.** Un file SQLite, date ISO, importi in centesimi.
- **Conferma umana.** Niente viene salvato senza che tu l'abbia visto.

## PENSY_DATA

```
PENSY_DATA/
  pensy.dat      il database, cifrato con AES-256-GCM (chiave da PBKDF2-SHA256, 200.000 iterazioni)
  pagine/        le foto conservate, una per file, cifrate allo stesso modo
  copie/         com'era pensy.dat negli ultimi 7 giorni
  versione.txt   formato, schema e data dell'ultimo salvataggio
  LEGGIMI.txt    come ripristinare i dati su un nuovo telefono
```

Il formato è descritto in `core/src/app/pensy/core/Vault.kt`; il file unico con le foto («Esporta» e
«Invia a un altro telefono») in `core/src/app/pensy/core/Pack.kt`.

## Firma

Le chiavi di firma **non sono nel repository** (`.gitignore` esclude `*.keystore`, `*.jks` e
`keystore.properties`): chi ha la chiave può firmare aggiornamenti a nome di PENSY. La build di rilascio
prende la chiave dalle variabili d'ambiente `PENSY_KEYSTORE`, `PENSY_KEYSTORE_PASSWORD`,
`PENSY_KEY_ALIAS`, `PENSY_KEY_PASSWORD`, oppure da `android/keystore.properties`
(modello in `android/keystore.properties.example`). Senza chiave usa quella di debug.

## Novità e licenza

- Cosa cambia in ogni versione: [CHANGELOG.md](CHANGELOG.md).
- Licenza: [LICENSE](LICENSE). Componenti di terzi e loro licenze: [THIRD_PARTY.txt](THIRD_PARTY.txt).
- Hai trovato un errore? Apri una segnalazione nella scheda *Issues*.

---

## English

**PENSY** turns everyday paperwork into text, deadlines and clear numbers, and keeps it filed in
folders. Take a photo of a bill, a medical report or a ticket, or load the PDF you got by email: PENSY
reads it on the phone, works out what it is, files it, keeps the QR or barcode and reminds you in time.
It also handles a monthly budget from your payslip, everyday spending, savings jars and investments,
a health record (conditions, medicines with schedules and stock, visits, measurements) and separate
profiles for family members and pets.

**Fully offline.** The app has no internet permission at all: no server, no account, no analytics.
Your data stays on the phone and, encrypted, in a `PENSY_DATA` folder you choose; to move to a new
phone, copy that folder and tap «Restore my data».

- **Download:** get the latest `PENSY-x.y.apk` from [Releases](../../releases) (Android 8.0+).
  New versions install over the old one and keep your data. iPhone: not yet.
- **Docs:** [user manual](docs/manual.md) and [FAQ](docs/faq.md). The app is in Italian and English.
- **Build:** open `android/` in Android Studio, or `cd android && ./gradlew assembleDebug`.
  Core tests: `KOTLINC=… ./tools/test-core.sh`.
- **Publishing a release:** see [docs/PUBBLICARE.md](docs/PUBBLICARE.md) (in Italian).
- **License:** see [LICENSE](LICENSE); third-party components in [THIRD_PARTY.txt](THIRD_PARTY.txt).
