# Aviation Weather

A personal Android app that puts METAR, TAF, satellite imagery and flight-planning checks for the Maldives in one place.

**For situational awareness only.** It does not replace official company weather sources, the briefing package or the aircraft's weather radar.

## Features

- **Airports** – live METAR and TAF, raw and decoded, with colour-coded flight category. Pull to refresh, reorder,
  add any airport by ICAO code, swipe between airports.
- **Airport page** – runway headwind / crosswind with diagram, TAF periods with the current one highlighted,
  12-hour trend graphs (wind, visibility, QNH), first light / sunrise / sunset / last light, winds and temperatures aloft, share.
- **Satellite map** – drawn base map with EUMETSAT infrared cloud laid over it in tiles, so parts already loaded
  stay on screen when you zoom or pan. Colour scale for cloud-top cold, rain-rate layer, cloud tops above a chosen
  flight level, SIGMET areas, and a 1 / 2 / 3 hour timeline with play, speed and scrubbing.
- **Wind** – animated wind lines over a speed colour wash, from the surface to FL240, up to 48 hours ahead;
  tap the map for the wind at a point. Icing risk as a second forecast layer.
- **Sector** – departure, destination and alternate with ETD / ETA: forecast around each time, change groups,
  thunderstorm flags, a check against your own minima, and day or night at that time.
- **Upper air** – freezing level, icing and turbulence risk by level, thunderstorm risk for the next 12 hours,
  a graphical TAF bar, and map layers for icing, wind arrows and cloud top height. These are estimates from model data.
- **Radar** – live aircraft on a satellite map (community ADS-B receivers, so coverage is partial): tap an aircraft
  for callsign, type, altitude, speed, track and squawk, with its trail and an optional cloud overlay.
- **Alerts** – optional notifications for an amended TAF, SPECI, a drop in flight category or a new thunderstorm.
- **Widget** – home-screen widget with the latest METAR for your destination or first airport.
- **Offline** – the last weather, images and map tiles are kept on the phone. **Night mode** turns the screen red.

## Not included

- **Lightning** – no free lightning feed covers the Maldives. A model-based thunderstorm risk is shown instead.
- **NOTAMs in the app** – a button opens the airport on Windy, where the NOTAM tab lists them; NOTAMs are not decoded in the app.

## Installing

Every push builds an APK with GitHub Actions. Open **Releases → Aviation Weather (latest build)** on your phone,
download `AviationWeather.apk` and install it.

## Data sources

- METAR / TAF: Aviation Weather Center, `aviationweather.gov/api/data`, plus the Maldives Meteorological Service
  aviation page (`v2.meteorology.gov.mv/aviation`); the newer report for each airport is used
- SIGMET: Aviation Weather Center
- Live traffic: airplanes.live, adsb.lol
- Radar imagery: Sentinel-2 cloudless – https://s2maps.eu by EOX IT Services GmbH (contains modified Copernicus Sentinel data 2024)
- Satellite and rain rate: EUMETSAT EUMETView WMS (`msg_iodc:ir108`)
- Winds aloft: Open-Meteo
- Map: © OpenStreetMap contributors, CARTO
