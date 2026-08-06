import xml.etree.ElementTree as ET

MASTER_FILE = r"D:\download\mappa\mappa laguna.osm"
UPLOAD_FILE = r"D:\download\mappa\per_upload_osm.osm"
APP_FILE = r"D:\download\mappa\laguna_per_app.osm"

# Tag che sono ESCLUSIVI della tua app e NON devono andare su OSM
# Usiamo prefissi precisi per non sbagliare
CUSTOM_TAG_KEYS = ['nav:', 'mare', 'laguna', 'gate', 'helper']

def process_osm():
    print("Suddivisione file OSM in corso (Versione Corretta)...")
    tree = ET.parse(MASTER_FILE)

    # 1. VERSIONE PER L'APP: Salviamo tutto
    tree.write(APP_FILE, encoding='UTF-8', xml_declaration=True)
    print(f"-> Generato file per l'APP: {APP_FILE}")

    # 2. VERSIONE PER UPLOAD: Pulizia chirurgica
    root = tree.getroot()
    elements_to_remove = []

    for elem in list(root):
        if elem.tag in ['way', 'node', 'relation']:
            tags = elem.findall('tag')

            # IMPORTANTE: Se un nodo NON ha tag, è un nodo di geometria.
            # DEVE essere tenuto altrimenti le linee (canali) spariscono!
            if not tags:
                continue

            tags_to_delete = []
            has_standard_tags = False

            for t in tags:
                k = t.get('k')
                # Controlliamo se il tag inizia con uno dei nostri prefissi custom
                is_custom = any(k.startswith(prefix) for prefix in CUSTOM_TAG_KEYS) or k in CUSTOM_TAG_KEYS

                if is_custom:
                    tags_to_delete.append(t)
                else:
                    # Se ha tag come man_made, waterway, name, seamark... è standard
                    has_standard_tags = True

            # Se l'elemento ha solo tag nostri (es. un'area no-go custom) lo eliminiamo del tutto
            if not has_standard_tags and tags:
                elements_to_remove.append(elem)
            else:
                # Se è un elemento misto (es. canale con tag nostri), togliamo solo i tag sporchi
                for t in tags_to_delete:
                    elem.remove(t)

    for elem in elements_to_remove:
        root.remove(elem)

    tree.write(UPLOAD_FILE, encoding='UTF-8', xml_declaration=True)
    print(f"-> Generato file per UPLOAD (PULITO): {UPLOAD_FILE}")

if __name__ == "__main__":
    process_osm()
