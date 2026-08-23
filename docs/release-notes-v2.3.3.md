# Timeline Visualizer 2.3.3

Adds a City Visited Recap option to the New Video screen for quickly reviewing the cities visited during the selected period.

The feature detects meaningful stops from Timeline data, filters out brief stops and GPS drift, and resolves locations into city and area names using the native Android Geocoder. Consecutive visits to the same city on the same day are grouped to keep the recap concise and avoid duplicate entries.

Unresolvable locations are skipped when no useful location can be determined, with a country-level fallback when available.
