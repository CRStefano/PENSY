# Pubblicare PENSY su GitHub

Questa cartella è il repository pronto: codice, manuale, licenza e i due «workflow» che su GitHub
controllano il codice e costruiscono l'APK da scaricare. Non contiene chiavi di firma né APK.

## Una volta sola

### 1. Crea il repository con GitHub Desktop

1. Installa [GitHub Desktop](https://desktop.github.com) ed entra con il tuo account GitHub.
2. *File › Add local repository…* e scegli questa cartella
   (`Documenti\PENSY\GitHub\PENSY`).
3. GitHub Desktop dice che non è ancora un repository: clicca **create a repository**.
   Nome `PENSY`. Lascia vuoti *Git ignore* e *License* (ci sono già) e non spuntare
   *Initialize this repository with a README*. Poi **Create repository**.
4. Dai un'occhiata all'elenco dei file: non deve esserci nessun `.keystore` né `.apk`
   (li esclude `.gitignore`).
5. **Publish repository**. Togli la spunta a *Keep this code private* se vuoi che chiunque possa
   scaricare l'app: con un repository privato la pagina *Releases* la vedi solo tu.

### 2. Metti la chiave di firma tra i «secret»

Android installa un aggiornamento solo se è firmato con **la stessa chiave** della versione già
installata. La chiave è `tools\pensy-test.keystore` nella cartella PENSY principale (non in questa) e
**non va mai caricata come file**: GitHub la tiene in un cassetto cifrato, un *secret*, che nessuno può
rileggere e che usa solo per firmare.

Su github.com, nel repository: *Settings › Secrets and variables › Actions › New repository secret*,
quattro volte:

| Nome                | Valore                                                                  |
|---------------------|-------------------------------------------------------------------------|
| `KEYSTORE_BASE64`   | tutto il testo del file `PENSY\tools\pensy-test.keystore.base64.txt`   |
| `KEYSTORE_PASSWORD` | la password del file `.keystore`                                        |
| `KEY_ALIAS`         | il nome della chiave dentro il file (alias)                             |
| `KEY_PASSWORD`      | la password della chiave (di solito la stessa)                          |

Password e alias sono scritti in `PENSY\tools\CHIAVE-LEGGIMI.txt`, sul tuo PC: non metterli in
nessun file di questa cartella, che è pubblica.

Il file `.base64.txt` è la chiave scritta come testo, per poterla incollare. Se un giorno ti serve
rifarlo, in PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\OneDrive\Documenti\PENSY\tools\pensy-test.keystore")) | Set-Clipboard
```

### 3. Il primo controllo

Appena pubblichi, nella scheda **Actions** parte «Controlli»: i test del nucleo e la costruzione
completa dell'app con Gradle. Una spunta verde vuol dire che è tutto a posto. Se diventa rossa, apri
il passaggio fallito, copia le ultime righe e mandamele.

## Ogni nuova versione

1. Aggiorna i file di questa cartella (quando preparo una versione lo faccio io, come per il resto
   del progetto).
2. In GitHub Desktop scrivi un riassunto (per esempio «Versione 0.9»), poi **Commit to main** e
   **Push origin**.
3. Su github.com: *Actions › Pubblica una versione › Run workflow*, scrivi la versione (`v0.9`) e
   premi **Run workflow**. In 5–10 minuti nella pagina *Releases* compare `PENSY-0.9.apk`, con le
   novità prese da `CHANGELOG.md`.

La versione che scrivi deve essere uguale a `versionName` in `android/app/build.gradle.kts`,
altrimenti il workflow si ferma prima di pubblicare (così non esce un APK con il numero sbagliato).

## Domande

**Costa qualcosa?** No. Con un repository pubblico i workflow sono gratuiti; con uno privato ci sono
2.000 minuti gratuiti al mese e una versione ne usa circa 10.

**Gli APK che mi mandi in chat e quelli di GitHub sono compatibili?** Sì: sono firmati con la stessa
chiave, quindi uno si installa sopra l'altro e i dati restano.

**La chiave di prova va bene?** Sì, finché il file `.keystore` resta solo sul tuo PC e nei secret. Se
preferisci una chiave con una password tua, il momento giusto è prima che altre persone installino
PENSY: chi ha la versione firmata con la vecchia chiave deve disinstallarla e recuperare i dati da
`PENSY_DATA`.

**Cosa non caricare mai?** File `.keystore`, `keystore.properties`, foto di documenti veri, la tua
cartella `PENSY_DATA`.

**OneDrive?** Un repository dentro OneDrive di solito funziona. Se OneDrive segnala conflitti su file
dentro `.git`, sospendi la sincronizzazione mentre fai *Commit* e *Push*, oppure sposta questa
cartella fuori da OneDrive.

**E l'iPhone?** È il prossimo passo, separato: richiede un progetto iOS a parte.
