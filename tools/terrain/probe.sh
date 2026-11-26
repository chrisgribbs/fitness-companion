#!/usr/bin/env bash
set -u
for n in 50 100 200 300; do
  P=$(python3 -c "import json;print(json.dumps([[152.99+i*0.00008,-27.475] for i in range($n)]))")
  curl -s -o /tmp/o.json -X POST "https://spatial-img.information.qld.gov.au/arcgis/rest/services/Elevation/QldDem/ImageServer/getSamples" \
    --data-urlencode "geometry={\"points\":$P,\"spatialReference\":{\"wkid\":4326}}" --data-urlencode "geometryType=esriGeometryMultipoint" \
    --data-urlencode "returnFirstValueOnly=true" --data-urlencode "f=json"
  python3 -c "import json;d=json.load(open('/tmp/o.json'));s=d.get('samples',[]);print($n,'->',len(s),'samples', d.get('error','') , [round(float(x['value']),1) for x in sorted(s,key=lambda x:x['locationId'])[:8] if x.get('value') not in (None,'NoData')])"
done
echo "== polyline sampling"
curl -s -o /tmp/p.json -X POST "https://spatial-img.information.qld.gov.au/arcgis/rest/services/Elevation/QldDem/ImageServer/getSamples" \
  --data-urlencode 'geometry={"paths":[[[152.99,-27.475],[153.03,-27.475]]],"spatialReference":{"wkid":4326}}' --data-urlencode "geometryType=esriGeometryPolyline" \
  --data-urlencode "sampleCount=300" --data-urlencode "returnFirstValueOnly=true" --data-urlencode "f=json"
python3 -c "import json;d=json.load(open('/tmp/p.json'));s=d.get('samples',[]);print('polyline',len(s),d.get('error',''))"
