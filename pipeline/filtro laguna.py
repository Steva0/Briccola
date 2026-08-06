import xml.etree.ElementTree as ET

# Configurazione percorsi
input_file = r"D:\download\mappa\mappa laguna.osm"
output_file = r"D:\download\mappa\laguna_pulita.osm"

def filtra_osm():
    print("Inizio filtraggio... potrebbe volerci un po' per file molto grandi.")
    
    # Primo passaggio: identifichiamo nodi e vie da tenere
    nodes_to_keep = set()
    ways_to_keep = []
    
    # Tag che ci interessano
    target_tags = {
        'waterway': {'canal'},
        'man_made': {'dolphin'},
        'seamark:type': {'pile', 'mooring'},
        'seamark:mooring:category': {'dolphin'}
    }

    context = ET.iterparse(input_file, events=('end',))
    
    for event, elem in context:
        if elem.tag == 'way':
            keep_way = False
            for tag in elem.findall('tag'):
                k, v = tag.get('k'), tag.get('v')
                if k in target_tags and v in target_tags[k]:
                    keep_way = True
                    break
            
            if keep_way:
                for nd in elem.findall('nd'):
                    nodes_to_keep.add(nd.get('ref'))
                # Salviamo i dati minimi della way per il secondo passaggio
                ways_to_keep.append(ET.tostring(elem))
            elem.clear()
            
        elif elem.tag == 'node':
            keep_node = False
            for tag in elem.findall('tag'):
                k, v = tag.get('k'), tag.get('v')
                if k in target_tags and v in target_tags[k]:
                    keep_node = True
                    break
            if keep_node:
                nodes_to_keep.add(elem.get('id'))
            elem.clear()

    print(f"Trovati {len(nodes_to_keep)} nodi e {len(ways_to_keep)} vie.")

    # Secondo passaggio: Scrittura del nuovo file
    with open(output_file, 'wb') as f:
        f.write(b'<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write(b'<osm version="0.6" generator="LagunaNavFilter">\n')
        
        # Ripartiamo con iterparse per i nodi (per non caricarli tutti in RAM)
        context = ET.iterparse(input_file, events=('end',))
        for event, elem in context:
            if elem.tag == 'node':
                if elem.get('id') in nodes_to_keep:
                    f.write(ET.tostring(elem))
                elem.clear()
        
        # Scriviamo le vie salvate
        for way_data in ways_to_keep:
            f.write(way_data)
            
        f.write(b'</osm>')
    
    print(f"Fatto! File salvato in: {output_file}")

if __name__ == "__main__":
    filtra_osm()