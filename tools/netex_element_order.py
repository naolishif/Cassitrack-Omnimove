"""
Stampa l'ordine dei figli che lo schema NeTEx impone a un tipo.

PERCHÉ SERVE
Gli XSD NeTEx usano xsd:sequence: l'ordine degli elementi non è una
convenzione estetica, è un vincolo. E i tipi si ereditano a catena —
ServiceJourney estende VehicleJourney estende Journey estende LinkSequence
estende DataManagedObject — quindi la sequenza vera è la concatenazione di
tutti i livelli, e non si trova leggendo un file solo.

Questo script segue la catena di estensione e concatena le sequenze, così
l'ordine si legge invece di indovinarlo.

Uso:
    python tools/netex_element_order.py <cartella-xsd> <NomeDelTipo> [altri...]

Esempio:
    python tools/netex_element_order.py ^
        "C:\\Users\\giaco\\Desktop\\University_2.0\\Distributed Computing\\NeTEx-2.0\\xsd" ^
        Line_VersionStructure ServiceJourney_VersionStructure Call_VersionStructure
"""
import os
import sys
import xml.etree.ElementTree as ET

XS = "{http://www.w3.org/2001/XMLSchema}"


def load_all_groups(xsd_dir):
    """
    Indicizza anche i named group.

    Senza questo lo script stampava vuoto pur trovando 2687 tipi: NeTEx non
    dichiara gli elementi dentro il complexType, li raccoglie in xsd:group
    riutilizzabili e nel tipo mette solo un riferimento. Cercando solo
    xsd:element si guardava la scatola invece del contenuto.
    """
    groups = {}
    for root, _dirs, files in os.walk(xsd_dir):
        for fn in files:
            if not fn.endswith(".xsd"):
                continue
            try:
                tree = ET.parse(os.path.join(root, fn))
            except Exception:
                continue
            for g in tree.getroot().iter(XS + "group"):
                name = g.get("name")
                if name:
                    groups.setdefault(name, g)
    return groups


def load_all_types(xsd_dir):
    """
    Indicizza ogni complexType di ogni .xsd sotto la cartella.

    Si scandiscono tutti i file invece di seguire gli xsd:include perché la
    catena di inclusioni di NeTEx è profonda e ramificata: leggere tutto una
    volta sola costa meno ed è più robusto di risolverla a mano.
    """
    types = {}
    for root, _dirs, files in os.walk(xsd_dir):
        for fn in files:
            if not fn.endswith(".xsd"):
                continue
            path = os.path.join(root, fn)
            try:
                tree = ET.parse(path)
            except Exception:
                continue          # qualche file dello standard non è parsabile da solo
            for ct in tree.getroot().iter(XS + "complexType"):
                name = ct.get("name")
                if name:
                    types.setdefault(name, (ct, fn))
    return types


def local(qname):
    """'netex:Foo' -> 'Foo'."""
    return qname.split(":")[-1] if qname else qname


def sequence_of(node, groups, depth=0):
    """
    Gli elementi di un tipo o di un gruppo, IN ORDINE, seguendo i riferimenti
    a xsd:group. Si scorre nell'ordine del documento, non con iter(), perche'
    qui l'ordine e' precisamente l'informazione cercata.
    """
    out = []
    if depth > 12:
        return out                    # guardia contro gruppi che si richiamano
    for child in node:
        tag = child.tag
        if tag == XS + "element":
            name = child.get("name") or local(child.get("ref"))
            if not name:
                continue
            minocc = child.get("minOccurs", "1")
            maxocc = child.get("maxOccurs", "1")
            card = "obbligatorio" if minocc != "0" else "opzionale"
            if maxocc not in ("1",):
                card += ", ripetibile"
            out.append((name, card))
        elif tag == XS + "group":
            ref = local(child.get("ref"))
            if ref and ref in groups:
                out += sequence_of(groups[ref], groups, depth + 1)
        else:
            # sequence, choice, complexContent, extension...: si scende
            out += sequence_of(child, groups, depth + 1)
    return out


def chain(types, type_name, seen=None):
    """Dalla radice della gerarchia fino al tipo richiesto."""
    seen = seen or set()
    if type_name in seen or type_name not in types:
        return []
    seen.add(type_name)
    ct, fn = types[type_name]
    ext = ct.find(f".//{XS}extension")
    base = local(ext.get("base")) if ext is not None else None
    parents = chain(types, base, seen) if base else []
    return parents + [(type_name, fn, ct)]


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    xsd_dir, wanted = sys.argv[1], sys.argv[2:]

    print(f"Indicizzo gli XSD in {xsd_dir} ...")
    types = load_all_types(xsd_dir)
    groups = load_all_groups(xsd_dir)
    print(f"  {len(types)} complexType, {len(groups)} group\n")

    for name in wanted:
        print("=" * 70)
        print(name)
        print("=" * 70)
        levels = chain(types, name)
        if not levels:
            print(f"  tipo non trovato. Nomi simili:")
            for k in sorted(types):
                if name.lower().split("_")[0] in k.lower():
                    print(f"    {k}")
            print()
            continue

        pos = 0
        for tname, fn, ct in levels:
            elems = sequence_of(ct, groups)
            if not elems:
                continue
            print(f"\n  --- da {tname}  ({fn})")
            for el, card in elems:
                pos += 1
                print(f"    {pos:3d}. {el:<38} {card}")
        print()


if __name__ == "__main__":
    main()
