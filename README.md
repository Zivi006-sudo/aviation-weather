# Aviation Weather

A personal Android app that puts METAR, TAF, satellite imagery and flight-planning checks for the Maldives in one place.

**For situational awareness only.** It does not replace official company weather sources, the briefing package or the aircraft's weather radar.

## Features

- **Airports** – live METAR and TAF, raw and decoded, with colour-coded flight category. Pull to refresh, reorder,
  add any airport by ICAO code, swipe between airports.
- **Airport page** – runway headwind / crosswind with diagram, TAF periods with the current one highlighted,
  12-hour trend graphs (wind, visibility, QNH), first light / sunrise / sunset / last light, winds and temperatures aloft, share.
- **Satellite map** – drawn base map with EUMETSAT infrared cloud laid over it, colour scale for cold cloud tops,
  rain-rate layer, SIGMET areas, 1 / 2 / 3 hour timeline with play, speed and scrubbing. The satellite follows the map as you pan and zoom.
- **Sector** – departure, destination and alternate with ETD / ETA: forecast around each time, change groups,
  thunderstorm flags, a check against your own minima, and day or night at that time.
- **Alerts** – optional notifications for an amended TAF, SPECI, a drop in flight category or a new thunderstorm.
- **Widget** – home-screen widget with the latest METAR for your destination or first airport.
- **Offline** – the last weather, images and map tiles are kept on the phone. **Night mode** turns the screen red.

## Not included

- **Lightning** – no free lightning feed covers the Maldives.
- **NOTAMs** – no free NOTAM source covers the Maldives.

## Installing

Every push builds an APK with GitHub Actions. Open **Releases → Aviation Weather (latest build)** on your phone,
download `AviationWeather.apk` and install it.

## Data sources

- METAR / TAF / SIGMET: Aviation Weather Center, `aviationweather.gov/api/data`
- Satellite and rain rate: EUMETSAT EUMETView WMS (`msg_iodc:ir108`)
- Winds aloft: Open-Meteo
- Map: © OpenStreetMap contributors, CARTO
