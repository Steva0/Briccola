import json
import urllib.request
from datetime import datetime

URL_TEMPLATE = "https://dati.venezia.it/sites/default/files/dataset/opendata/as{year}est.json"
OUTPUT_FILE = "marea_astronomica.json"


def scarica_anno(year: int):
    url = URL_TEMPLATE.format(year=year)
    req = urllib.request.Request(url, headers={"User-Agent": "OpenLagunaMaps/1.0"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def run():
    anno_corrente = datetime.now().year
    estremali = scarica_anno(anno_corrente)

    # Includiamo anche i primi giorni dell'anno successivo, così l'interpolazione
    # della curva di stanotte/domani a fine dicembre ha un estremale "dopo" su cui appoggiarsi.
    try:
        prossimo = scarica_anno(anno_corrente + 1)
        estremali += [e for e in prossimo if e["DATA"].startswith(f"{anno_corrente + 1}-01-0")]
    except Exception:
        pass

    dati = {"anno": anno_corrente, "estremali": estremali}
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(dati, f, ensure_ascii=False)

    print(f"Scaricati {len(estremali)} estremali di marea astronomica per l'anno {anno_corrente}")


if __name__ == "__main__":
    run()
