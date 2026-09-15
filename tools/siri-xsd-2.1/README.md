# SIRI 2.1 XML Schemas

The official CEN SIRI 2.1 schemas (EN 15531), copied verbatim from
https://github.com/TransmodelEcosystem/SIRI, tag `v2.1`, `xsd/` folder —
`.xsd` files only (WSDL, examples and IDE project files left out).
The directory layout is the upstream one because the schemas `include`
each other by relative path.

Used to check what CassiTrack publishes:

```bash
curl -s http://localhost:8280/cassitrack/api/v1/siri/vehicle-monitoring > vm.xml
python3 validate_siri.py tools/siri-xsd-2.1/siri.xsd vm.xml
```

Same for `/api/v1/siri/stop-monitoring?MonitoringRef=PSB`,
`/api/v1/siri/check-status` and the `data:` payload of the SSE stream.
Needs `pip install lxml`.

CassiTrack declares `version="2.1"`; the output also validates against the
2.2 schemas (tag `v2.2`), which are backward compatible.
