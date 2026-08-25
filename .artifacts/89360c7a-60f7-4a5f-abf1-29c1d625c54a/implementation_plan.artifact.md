# Ridimensionamento e Posizionamento Pulsanti Marea

L'utente desidera ridurre la dimensione dei pulsanti delle opzioni di marea ("Min", "+6h", "+3h", "Ora") o avere la possibilità di regolarne scala e posizione (X, Y) dal menù Dev, similmente ad altri elementi della UI.

## Proposta di Modifica

Verranno aggiunti nuovi parametri di tuning per il layout delle opzioni di marea e implementati i relativi controlli nel pannello Dev Tools.

### [Component Name] Engine

#### [MODIFY] [UiTuning.kt](file:///C:/Users/miche/AndroidStudioProjects/OpenLagunaMaps/app/src/main/java/com/briccola/app/engine/UiTuning.kt)
- Aggiunta di `bathyOptionsScale` (default 0.8f per ridurli leggermente come richiesto).
- Aggiunta di `bathyOptionsOffsetXDp` (default 102f).
- (Nota: `bathyBtnOffsetYDp` verrà riutilizzato per l'altezza Y per mantenerli allineati al pulsante layer).

---

### [Component Name] UI

#### [MODIFY] [MapFragment.kt](file:///C:/Users/miche/AndroidStudioProjects/OpenLagunaMaps/app/src/main/java/com/briccola/app/ui/MapFragment.kt)
- Aggiornamento di `applyUiTuning()` per applicare la nuova scala e l'offset X al `layoutBathyOptions`.

#### [MODIFY] [DevToolsFragment.kt](file:///C:/Users/miche/AndroidStudioProjects/OpenLagunaMaps/app/src/main/java/com/briccola/app/ui/DevToolsFragment.kt)
- Aggiunta dei controlli (TextView e SeekBar) per `bathyOptionsScale` e `bathyOptionsOffsetXDp`.
- Aggiornamento della logica di sincronizzazione e aggiornamento dei parametri.

#### [MODIFY] [fragment_devtools.xml](file:///C:/Users/miche/AndroidStudioProjects/OpenLagunaMaps/app/src/main/res/layout/fragment_devtools.xml)
- Inserimento dei nuovi elementi UI nel pannello "Settaggi Dev".

## Piano di Verifica

### Test Manuali
1. Aprire l'app in modalità Mappa normale e verificare la nuova dimensione di default dei pulsanti marea.
2. Aprire Dev Tools > Settaggi Dev.
3. Regolare lo slider "Scala opzioni marea" e verificare il ridimensionamento immediato.
4. Regolare lo slider "Posizione opzioni marea (X)" e verificare lo spostamento orizzontale.
5. Verificare che il tasto "Reset valori default" ripristini correttamente anche questi nuovi parametri.
