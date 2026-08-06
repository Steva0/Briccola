# Briccola
### Sistema di navigazione cartografica assistita per la Laguna di Venezia

Briccola è un'applicazione Android di navigazione nautica specializzata per l'ambiente lagunare veneziano. Il progetto nasce per integrare dati batimetrici ad alta precisione con algoritmi di navigazione intelligente, offrendo una consapevolezza situazionale superiore rispetto ai navigatori generalisti.

## Missione
La missione di Briccola è fornire ai navigatori lagunari uno strumento di precisione che permetta una navigazione sicura e consapevole, integrando dati in tempo reale sulla profondità dei fondali e sulle maree. Il progetto si impegna a garantire la massima affidabilità tecnologica attraverso un'architettura totalmente offline-first, tutelando la privacy dell'utente attraverso l'assenza di tracciamento e la gestione locale di ogni dato sensibile.

## Caratteristiche Tecniche e Funzionali

### Gestione Cartografica Ibrida
L'app implementa un'architettura di rendering vettoriale basata su MapLibre Native. Per garantire la continuità operativa anche in assenza di copertura di rete, il pacchetto d'installazione include un dataset offline completo per l'intera area lagunare (raggio di 35km). Per le aree esterne, il sistema commuta automaticamente su una modalità on-demand scaricando tile vettoriali e raster in tempo reale.

### Motore Batimetrico e Correzione Marea
A differenza dei comuni software di navigazione, Briccola integra un motore batimetrico che interpola i dati dei rilievi ufficiali con il livello di marea astronomica corrente. Questo permette di visualizzare una stima realistica della profondità sotto la chiglia in tempo reale, parametrizzata sulla posizione GPS del natante.

### Routing e Navigazione di Precisione
Il sistema include un motore di calcolo dei percorsi ottimizzato per la rete di canali lagunari.
- Calcolo della rotta ottimale basato sulla rete idroviaria esistente.
- Stima dei tempi di arrivo (ETA) basata su limiti di velocità specifici per ogni area.
- Istruzioni visive per il mantenimento della rotta e segnalazione automatica dello scostamento dai canali navigabili.

### Strumentazione Dinamica
L'interfaccia utente adatta la propria strumentazione in base allo stato del sensore GNSS. Tachimetro e altimetro batimetrico vengono visualizzati esclusivamente in presenza di un segnale GPS valido per evitare la consultazione di dati obsoleti.

### Architettura Privacy-by-Design
Il sistema è progettato per operare in isolamento:
- Nessun server centrale: il calcolo dei percorsi e l'analisi dei dati avvengono localmente sul dispositivo.
- Assenza di telemetria: non vengono raccolti né trasmessi identificativi utente, dati sulla posizione o cronologia di navigazione.
- Ricerca geografica: le query verso servizi esterni (Nominatim) avvengono in modo anonimo e solo su esplicita richiesta dell'utente.

## Stack Tecnologico
- **Linguaggio**: Kotlin.
- **Rendering Motore**: MapLibre Native.
- **Server Locale**: NanoHTTPD per la gestione dei database SQLite di tile, glifi e sprite (formato MBTiles).
- **Elaborazione Dati**: Pipeline Python custom per il filtraggio e la trasformazione di dati OpenStreetMap e raster batimetrici.

## Distribuzione
Le versioni stabili e i pacchetti di installazione APK sono disponibili nella sezione dedicata alle release del repository.

## Licenza
Questo software è distribuito sotto la **PolyForm Noncommercial License 1.0.0**. L'uso per scopi commerciali è severamente vietato.
