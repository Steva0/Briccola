# Redesign Layout Controlli Marea

L'obiettivo è migliorare l'usabilità e la pulizia visiva della mappa spostando il grafico della marea in un pannello inferiore (stile Bottom Sheet) e posizionando solo i controlli essenziali (OFF, ORA) in alto a destra.

## Proposed Changes

### UI Layout (`:app`)

#### [MODIFY] [fragment_map.xml](file:///C:/Users/miche/AndroidStudioProjects/OpenLagunaMaps/app/src/main/res/layout/fragment_map.xml)
- Rimozione del `LinearLayout` `layout_bathy_options` attuale.
- Aggiunta di un nuovo `LinearLayout` in alto a destra (sotto o accanto al tasto Layer) contenente i pulsanti circolari `OFF` e `ORA`.
- Aggiunta di una `CardView` a tutta larghezza in basso (`card_tide_panel`) per ospitare il `TideSliderView` (grafico marea).
- Posizionamento del pannello inferiore in modo che non si sovrapponga agli strumenti (tachimetro/altimetro) quando aperto.

### Logic (`:app`)

#### [MODIFY] [MapFragment.kt](file:///C:/Users/miche/AndroidStudioProjects/OpenLagunaMaps/app/src/main/java/com/briccola/app/ui/MapFragment.kt)
- Aggiornamento dei riferimenti ai nuovi ID (`btn_bathy_off`, `btn_bathy_now`, `card_tide_panel`).
- Modifica di `toggleBathyHeatmap` per aprire il pannello in basso invece di mostrare le opzioni in alto.
- Aggiornamento di `updateBathyOptionsUi` per gestire lo stato dei nuovi pulsanti.
- Modifica del pulsante `OFF` affinché chiuda il pannello e nasconda la heatmap (come richiesto).
- Aggiornamento di `applyUiTuning` per posizionare correttamente i nuovi elementi (gestione insets/edge-to-edge).
- Inserimento di `card_tide_panel` nella logica di `isAnyOverlayOpen` per nascondere gli altri strumenti quando il grafico è visibile.

## Verification Plan

### Manual Verification
- Verificare che il tasto Layer apra il pannello in basso con il grafico grande.
- Verificare che il tasto ORA funzioni correttamente evidenziando il momento attuale sul grafico.
- Verificare che il tasto OFF chiuda il pannello e disattivi il layer della batimetria.
- Verificare che il layout sia pulito e non ci siano sovrapposizioni con la barra di navigazione o la barra di stato (Edge-to-Edge).
