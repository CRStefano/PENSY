# Novità

Cosa cambia in ogni versione di PENSY. Le versioni pubblicate si scaricano dalla pagina *Releases*.

*English: the changes of each version, written in Italian. The app itself is fully translated.*

## 0.9

**Altre entrate**: i soldi arrivati fuori dalla busta paga (un amico che restituisce, un regalo, una vendita, un
lavoretto, un rimborso) si aggiungono in «Bilancio» › «Aggiungi un'entrata» o da «Aggiungi» › «Nuova entrata».
Si sommano alle entrate del mese senza toccare la busta paga né la sua stima; compaiono nel bilancio, in
«Andamento» e nell'esportazione «Entrate» in CSV.

**Carta o contanti**: ogni spesa ed entrata dice come si sono mossi i soldi («Carta o conto» o «Contanti»,
ricordando l'ultima scelta). Il bilancio del mese mostra quanto è passato con la carta e quanto in contanti; il 730
usa la stessa informazione per lasciare fuori le spese in contanti che non si detraggono. Niente due portafogli con
due saldi: il perché è nel manuale.

Database versione 7 (tabella `inflow`). Nel nucleo `Inflow`, `InflowCat`, `Budget.cashSplit`; `Budget` e
`Trend` contano le altre entrate (473 test).

## 0.8

**Farmaci**
- quando: ogni giorno, ogni N giorni, ogni N settimane, alcuni giorni della settimana, ogni mese (il giorno 15
  oppure «il primo lunedì», anche ogni 2, 3, 6, 12 mesi), una volta sola, al bisogno; avviso e riordino per tutti;
- l'orario è facoltativo: senza orario la dose è «in giornata», in «Oggi» con ✓/✕, e il promemoria arriva all'ora
  dei promemoria (con gli stessi pulsanti), senza sveglia;
- la siringa tra le forme del farmaco;
- scorte a scatole: quante ne hai, quante dosi in una scatola, quante tenerne da parte; quando restano solo quelle,
  PENSY ricorda di ordinare;
- nel farmaco «Prossima volta» anche per quelli mensili o senza orario.

**Salute**: patologie e allergie «dalla nascita»; interventi in programma nel calendario con promemoria a una
settimana, il giorno prima e il giorno stesso; l'altezza tra le misurazioni, con l'indice di massa corporea.

**Calendario**: il giorno toccato (anche rosso) si vede subito sotto la griglia, solo con quello che c'è quel
giorno; compaiono interventi, dosi dei farmaci non quotidiani e la salute delle altre persone, con il loro nome.

**Icona**: il nuovo logo (la P tracciata in vettoriale dal disegno originale).

Database versione 6 (`medicine.rule`, `medicine.reserve`, `health_item.from_birth`). Nel nucleo `MedRule` e le
scorte a scatole in `Health.kt` (448 test).

## 0.7

**Foto o file, ovunque**: ogni volta che si aggiunge qualcosa (documento, busta paga, referto di una visita,
ricevuta, pagine in più, tasto del widget) si sceglie «Scatta una foto» o «Carica un file (PDF o immagine)».
Più immagini scelte insieme diventano le pagine di un solo documento; da un'altra app si può condividere anche
più di una foto, e un PDF si apre con «Apri con» › PENSY.

**Più facile all'inizio**
- primo avvio in 3 passi che fanno qualcosa: lingua e nome, per chi la usi (crea già i profili), dove salvare
  `PENSY_DATA`, oppure «Recupera i miei dati» dal vecchio telefono;
- «Primi passi» in «Oggi», che si spuntano da soli (primo documento, busta paga, cartella, notifiche, Salute);
- un suggerimento di una riga alla prima apertura di Fascicoli, Calendario, Bilancio, Salute, Andamento, Investimenti;
- il menu «Aggiungi» diviso in «Da un documento» e «Senza documento»;
- dopo il salvataggio, una riga dice dove è finito e quando arriverà l'avviso, con «Vedi»;
- **manuale d'uso** nell'app (17 capitoli, italiano e inglese): cosa fa ogni sezione, come si usa, a cosa serve,
  consigli e il perché delle scelte. Dal primo avvio, dal tour, da Impostazioni (icona del libro) e dalla guida;
  si cerca e si salva in PDF. Testo in `res/raw/manuale.txt` e `res/raw-en/manuale.txt`.

**Risparmi e investimenti**: salvadanai e investimenti insieme, con il totale messo da parte. Ogni investimento
(ETF, azioni, obbligazioni, fondo, fondo pensione, conto deposito, buoni, cripto) ha versamenti e prelievi con le
quote, un piano di accumulo che si registra da solo (anche all'indietro) e conta nel bilancio, i valori copiati
dalla banca o dal broker (valore totale o prezzo di una quota: nessun prezzo scaricato), guadagno in euro e in %,
grafico di com'è andata e proiezione a 5, 10, 20, 30 anni con il rendimento medio scelto. Calcolatore di accumulo.

**Andamento**: i mesi messi insieme (6 mesi, 12 mesi, anno, tutto): entrate e uscite con medie mensili,
anno per anno, spese per categoria sull'intero periodo, stipendio netto nel tempo. Solo ciò che è registrato.

**Correzione di Stefano** per Samsung e Motorola: l'aspetto delle barre di sistema si imposta quando la
finestra è pronta.

Database versione 5 (`investment`, `invest_move`, `invest_value`). Nel nucleo `Invest.kt` e `Trend.kt`; il
bilancio conta anche i soldi investiti (414 test). Nuovi file: `Onboarding.kt`, `Manual.kt`, `Charts.kt`
(un grafico generale a barre e linee), `InvestUi.kt`, `TrendScreen.kt`.

## 0.6

**Documenti**
- carta d'identità, patente (campo 4b), passaporto: scadenza letta o calcolata dalla data di rilascio; avvisi a 90, 30 e 7 giorni;
- garanzie: fine a due anni dall'acquisto (o quanto scritto), prezzo tenuto nel documento;
- abbonamenti e contratti: «Disdici entro» come promemoria a sé; in «Bilancio» le «Spese fisse» con il costo annuo;
- ricevute e abbonamenti riconosciuti come tipi; parole e date anche in inglese;
- un fascicolo intero in PDF (da fare, indice, ogni documento con le sue foto o il testo).

**Soldi**: «Spese per il 730» (spese mediche, veterinario, scuola, università, mezzi, sport dei figli) con stima al 19%
e CSV per il CAF; esportazione in CSV di scadenze e spese (si apre con Excel).

**Auto e animali**: «Promemoria dell'auto» (revisione a 4 anni e poi ogni 2, assicurazione, bollo, tagliando, gomme)
e «Promemoria dell'animale» (vaccino con richiamo, antiparassitario, visita annuale, peso).

**Comodità**: widget per la schermata Home; copie degli ultimi 7 giorni in `PENSY_DATA/copie/` con «Ripristina una copia»;
lettura dei documenti anche in inglese (modello inglese nell'APK; il lettore nativo ora cambia lingua senza riavvio).

Nel nucleo: `Tax.kt` (730), `CarRules.kt`, spese fisse in `Budget.kt`, documenti e garanzie in `Parser.kt` (356 test).

## 0.5

**Salute**
- dosi segnate come prese o saltate, in «Oggi», nel fascicolo e dalla notifica («Presa», «Salta», «Tra 10 minuti»);
  le dosi saltate non consumano le scorte; aderenza degli ultimi 30 giorni; «Presa ora» per le medicine al bisogno;
- le medicine possono suonare anche in silenzioso (canale con il suono della sveglia);
- misurazioni (pressione, glicemia, peso, temperatura, saturazione, battito) con grafico, minimo/media/massimo e CSV;
- «Mostra al medico» › PDF con scheda, grafici degli ultimi tre mesi e aderenza;
- scheda di emergenza, anche sulla schermata di blocco (per scelta, come notifica silenziosa);
- richiami dei vaccini con avvisi a 30 e 7 giorni e il giorno stesso; nel calendario;
- ricetta elettronica: PENSY legge l'NRE e ricorda che una ricetta per farmaci vale 30 giorni; «Mostra al farmacista».

Nuovi file: `Pdf.kt` (PDF disegnati a mano con il framework), `MeasureUi.kt`, `Emergency.kt`; nel nucleo
`Csv.kt` e, in `Health.kt`, registro dosi, aderenza, richiami e misurazioni (312 test).

## 0.4

**Documenti di più pagine, foto a scelta**
- «Aggiungi pagina» prima di salvare e anche dopo; un PDF viene letto tutto (fino a 12 pagine);
- «Conserva la foto» documento per documento, o sempre (Impostazioni › Riservatezza);
- le foto si sfogliano a schermo intero (pizzica per ingrandire) e si possono condividere;
- in `PENSY_DATA/pagine/` ogni foto è un file cifrato a sé: un salvataggio non le riscrive.

**Ricevute**: «Allega la ricevuta» da una scadenza; resta nello stesso fascicolo, con la foto.

**Blocco con impronta o PIN**: all'avvio e dopo un minuto fuori; usa il blocco del telefono.

**Persone**: profili per i figli o un genitore assistito, ognuno con fascicoli, scadenze e
salute propri; si cambia dal cerchio in «Oggi»; gli avvisi arrivano per tutti, con il nome;
le spese di un figlio possono contare nel bilancio del genitore.

**Spese di tutti i giorni e salvadanai**
- una spesa in tre tocchi (importo, categoria, salva); nel bilancio per categoria;
- salvadanai con obiettivo e data: PENSY suggerisce quanto mettere ogni mese e lo toglie da «ti restano».

**Promemoria a più tappe**: con un primo avviso lontano (es. 90 giorni) arrivano anche quelli a 30 e a 7 giorni.

**Invia a un altro telefono**: un unico file cifrato (con le foto) tramite la condivisione di Android.

Il database passa allo schema 4 (persone, spese, salvadanai, foto, e le tabelle per le
prossime versioni). L'aggiornamento dalla 0.3 è automatico.

## 0.3.2

Corregge la chiusura all'avvio della 0.3 (un ordinamento della libreria Kotlin che
su Android non funziona) e aggiunge una pagina che, se PENSY si chiude per un
errore, mostra il testo dell'errore da copiare.

## 0.3

**Scheda salute** (nel fascicolo Salute, e per ogni animale in Animali)
- patologie, allergie, interventi e vaccini;
- farmaci con dose, orari, ogni quanti giorni, dal/fino al; le dosi di oggi in vista; avviso all'ora esatta;
- scorte: PENSY conta le compresse e avvisa quando ordinare, oppure «ordino ogni N giorni»; «Ho comprato una confezione»;
- visite ed esami: da prenotare (anche entro la data della priorità U/B/D/P), prenotate (giorno, ora, luogo), fatte, con il referto collegato;
- i controlli trovati nei referti diventano visite da prenotare; visite e riordini anche nel calendario e in «Oggi»;
- «Mostra al medico»: una pagina pulita a caratteri grandi, condivisibile come testo.

**Scadenze senza data**
- «Nessuna data di scadenza»: si attiva da sola quando segni «Già pagata» (si può disattivare); la spesa conta nel mese in cui l'hai pagata.

**Primo avvio, lingua, guida**
- tour guidato in sette pagine (si rivede da Impostazioni o dalla guida);
- lingua: come il telefono, italiano o inglese, tutta l'app tradotta;
- il punto di domanda in «Oggi» apre la guida: 51 domande e risposte su come funziona PENSY e su dove andare per fare le cose (pagoPA, bollo, F24, CUP, ricette, patente, carta d'identità…), con ricerca.

## 0.2

**Lettura e smistamento automatici**
- foto, immagine o PDF → testo letto sul telefono (Tesseract, modello italiano) → scheda da confermare; la foto viene cancellata;
- riconosce bollette, avvisi pagoPA, bollo auto, multe, ticket, referti e visite, ricette, tasse universitarie, rate, F24, assicurazioni, documenti personali, garanzie, buste paga;
- legge importo, scadenza, data del documento, codice avviso pagoPA; dal QR pagoPA prende l'importo esatto;
- nei referti trova i controlli da fare ("controllo tra 12 mesi") e propone il promemoria;
- sceglie da solo il fascicolo e il sottofascicolo (un referto cardiologico va in Salute › Cuore se il sottofascicolo esiste) e impara dalle tue scelte: se sposti un documento di un ente, i successivi dello stesso ente andranno lì.

**Fascicoli**
- Casa, Auto, Salute, Animali, Scuola e università, Lavoro, Tasse e documenti, Altro, più quelli che crei tu;
- sottofascicoli liberi, a qualsiasi profondità (Salute › Gamba, Auto › Panda…);
- ogni fascicolo ha un riepilogo sempre a portata di mano (in Salute: patologie, allergie, farmaci, gruppo sanguigno, medico);
- dentro: cosa c'è da fare, i sottofascicoli, i documenti; ogni documento si può spostare;
- ricerca nel testo di tutti i documenti ("gamba", "cardiolog", la targa…).

**Pagamenti e scadenze** (dalla 0.1)
- calendario, ricorrenze, avviso 10 giorni prima (regolabile), poi il giorno prima e il giorno stesso;
- busta paga → "ti restano X € questo mese", mesi pesanti, grafico di 12 mesi;
- QR e codici a barre ridisegnati identici, da mostrare o scansionare.

**Dati**
- cartella `PENSY_DATA` aggiornata da sola e cifrata, esportazione in un unico file, importazione su un altro telefono.
