import xml.etree.ElementTree as ET

INPUT_FILE = r"D:\download\mappa\laguna_pulita.osm"
OUTPUT_FILE = r"D:\download\mappa\briccole_per_upload.osm"

# Tag standard OSM per le briccole
BRICCOLE_TAGS = {
    'man_made': ['dolphin'],
    'seamark:type': ['pile', 'dolphin', 'mooring'],
    'seamark:mooring:category': ['dolphin']
}

def estrai_briccole():
    print(f"Estrazione briccole da {INPUT_FILE} (Solo aggiunte/modifiche)...")
    tree = ET.parse(INPUT_FILE)
    root = tree.getroot()

    keep_elements = []

    for elem in root:
        if elem.tag in ['node', 'way']:
            # SALTO CRITICO: Se l'elemento è segnato come 'delete', lo ignoriamo
            if elem.get('action') == 'delete':
                continue

            tags = {t.get('k'): t.get('v') for t in elem.findall('tag')}

            is_briccola = False
            for k, values in BRICCOLE_TAGS.items():
                if k in tags and tags[k] in values:
                    is_briccola = True
                    break

            if is_briccola:
                # Pulizia tag custom prima dell'upload
                for t in list(elem.findall('tag')):
                    if t.get('k').startswith('nav:') or t.get('k') in ['mare', 'laguna']:
                        elem.remove(t)
                keep_elements.append(elem)

    # Creazione del nuovo file OSM
    new_root = ET.Element('osm', version='0.6', generator='BriccoleExtractor')
    for e in keep_elements:
        new_root.append(e)

    new_tree = ET.ElementTree(new_root)
    new_tree.write(OUTPUT_FILE, encoding='UTF-8', xml_declaration=True)

    print(f"Fatto! Trovate {len(keep_elements)} briccole valide (zero cancellazioni).")
    print(f"File salvato in: {OUTPUT_FILE}")

if __name__ == "__main__":
    estrai_briccole()
