"""Check English/Serbian string coverage and formatted argument compatibility."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / 'android-app/app/src/main/res'
def read(folder):
    entries = ET.parse(root / folder / 'strings.xml').getroot().findall('string')
    result = {entry.attrib['name']: ''.join(entry.itertext()) for entry in entries}
    assert len(result) == len(entries), f'Duplicate string names in {folder}'
    return result

english, serbian = read('values'), read('values-b+sr+Latn')
assert english.keys() == serbian.keys(), f'Missing translations: {english.keys() ^ serbian.keys()}'
def arguments(text):
    return sorted(re.findall(r'%(\d+)\$[\d.]*([sdf])', text))
for name in english:
    assert arguments(english[name]) == arguments(serbian[name]), name
for name in ('set_preset_battery', 'set_preset_balanced', 'set_preset_quality'):
    assert '~' not in english[name] and '~' not in serbian[name], 'Remove unmeasured forecasts'
print(f'PASS: {len(english)} English/Serbian strings, unique names and matching format arguments.')
