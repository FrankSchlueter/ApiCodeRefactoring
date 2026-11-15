---
title: "wie funktioniert neo4j batch unwind"
source: "https://www.google.com/search?q=wie+funktioniert+neo4j+batch+unwind&sourceid=chrome&ie=UTF-8&amc=1&gs_lcrp=EgZjaHJvbWUyBggAEEUYOdIBCjIzNDcxajBqMzGoAgCwAgHqBwoIARAAGAAgACgA&oq=wie+funktioniert+neo4j+batch+unwind&udm=50&aep=42&cud=0&source=chrome.crn.rb&mstk=AUtExfB8IuMWaofWA7LJDgOcF55SBsGfZfqf-N1sVqHlG8ilLQS75xJknjvfbSkLQO1DqSWleKUE2KvIdLCoByPoB_VpLNLHBIFVRyWzCmKp4mh4LnXMkhmzI1RqmdiP3zI9_hlMitqRJWazjrKhbTUhWL77NMfwFDzZsjE&csuir=1&mtid=e5_4atWfLciFxc8PiofToQY"
author:
published:
created: 2026-11-15
description:
tags:
  - "clippings"
---
## Unterhaltung im KI-Modus: wie funktioniert neo4j batch unwind

wie funktioniert neo4j batch unwind

**Ergebnisse werden gesucht**

### Antwort des KI-Modus für „wie funktioniert neo4j batch unwind“

==In Neo4j verwandelt der Befehl **`UNWIND` eine Liste von Datenobjekten in einzelne Datenbank-Zeilen**, sodass Tausende Datensätze in einer einzigen Anfrage verarbeitet werden können==. Anstatt für jeden Knoten oder jede Beziehung ein separates `CREATE` oder `MERGE` über das Netzwerk zu senden, überträgst du ein ganzes Datenpaket (Batch) als Parameter.

Neo4j +2

Das Grundprinzip (Liste zu Zeilen)

`UNWIND` ist das genaue Gegenteil von `COLLECT()`. Während `COLLECT()` viele einzelne Zeilen zu einer Liste zusammenfasst, entpackt `UNWIND` eine Liste wieder in einzelne Verarbeitungszeilen.

Neo4j Online Community

Stell dir vor, du übergibst aus deinem Anwendungscode (z. B. Python, Java oder JavaScript) einen Parameter namens `$batch`, der eine Liste von Objekten (Maps/Dictionaries) enthält:

Medium·Michael Hunger +1

```
{
  "batch": [
    { "id": 1, "name": "Anna", "stadt": "Hamburg" },
    { "id": 2, "name": "Lukas", "stadt": "Berlin" },
    { "id": 3, "name": "Mia", "stadt": "München" }
  ]
}
```

Die dazugehörige Cypher-Abfrage sieht so aus:

```
UNWIND $batch AS row
MERGE (p:Person {id: row.id})
SET p.name = row.name,
    p.stadt = row.stadt
```

**Was passiert hier Schritt für Schritt?**

1. **`UNWIND $batch AS row`**: Neo4j nimmt die Liste `$batch` und erzeugt daraus 3 separate Zeilen. In jeder Zeile steht unter der Variablen `row` genau ein Objekt aus der Liste.
2. **`MERGE` & `SET`**: Alle folgenden Befehle werden nun automatisch für jede dieser Zeilen ausgeführt. Der Query-Planer von Neo4j kompiliert die Abfrage nur ein einziges Mal und nutzt den Plan für alle Einträge im Batch wieder.
	Medium·Michael Hunger

Warum ist das so viel schneller?

- **Weniger Netzwerk-Overhead:** Du hast nur noch einen einzigen Roundtrip zwischen deiner Anwendung und der Datenbank statt Tausenden Einzelanfragen.
	GitHub +1
- **Eine Transaktion:** Alle Änderungen im Batch laufen in einer gemeinsamen Transaktion ab, was Schreibvorgänge auf der Festplatte massiv beschleunigt.
- **Query-Caching:** Da die Query-Struktur durch den Parameter `$batch` immer gleich bleibt, kann Neo4j den Ausführungsplan im Cache behalten.
	Medium·Michael Hunger

Wichtige Best Practices für die Praxis

- **Optimale Batch-Größe wählen.** Sende nicht Millionen Einträge auf einmal, da sonst der Arbeitsspeicher (Heap) der Datenbank überläuft. Eine gute Richtgröße liegt meist zwischen **1.000 und 10.000 Einträgen** pro Batch.
- **Vorher Indizes anlegen.** Wenn du im Batch `MERGE` oder `MATCH` nutzt, muss die gesuchte Eigenschaft (im Beispiel `p.id`) unbedingt einen Index oder Unique Constraint besitzen. Ohne Index wird jeder Eintrag im Batch extrem langsam.
- **Knoten und Beziehungen trennen.** Lade zuerst alle Knoten per `UNWIND` und erstelle erst in einem zweiten Schritt die Beziehungen dazwischen. So vermeidest du Sperrkonflikte (Locks) bei parallelen Schreibvorgängen.
- **Riesige Datenmengen aufteilen.** Wenn du sehr große CSV-Dateien oder interne Daten direkt in Cypher verarbeitest, kombiniere `UNWIND` mit `CALL { ... } IN TRANSACTIONS OF 5000 ROWS`. Dadurch teilt Neo4j die Arbeit intern selbstständig in speicherschonende Teil-Transaktionen auf.
	Stack Overflow +3

Weitere Details zur Syntax findest du im offiziellen [Neo4j Cypher Manual zu UNWIND](https://neo4j.com/docs/cypher-manual/current/clauses/unwind/) sowie im [Neo4j Data Ingestion Guide](https://neo4j.com/docs/data-ingestion-guide/ingestion-methods/unwind/).

Möchtest du ein konkretes Code-Beispiel für deine **Programmiersprache** (z. B. Python oder JavaScript) sehen oder wissen, wie man mit `UNWIND` **Beziehungen zwischen bestehenden Knoten** anlegt?

KI-Antworten können Fehler enthalten. [Weitere Informationen](https://support.google.com/websearch?p=aimode)

In Zwischenablage kopiertFehler beim Kopieren in die Zwischenablage. Versuche es später noch einmal.

## Geteilt

0 Dateien

- Medium·Michael Hunger
	5 Tips & Tricks for Fast Batched Updates of Graph Structures with Neo4j and Cypher | by Michael Hunger | Neo4j Developer Blog | Medium
	August 11, 2017 — Prefix single-update queries with UNWIND for batches (10k-50k entries) of maps to enable query compilation and caching.
	![](data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAkGBwgHBgkIBwgKCgkLDRYPDQwMDRsUFRAWIB0iIiAdHx8kKDQsJCYxJx8fLT0tMTU3Ojo6Iys/RD84QzQ5OjcBCgoKDQwNGg8PGjclHyU3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3Nzc3N//AABEIAFIAUQMBIgACEQEDEQH/xAAcAAACAwEBAQEAAAAAAAAAAAAAAwECBwQFBgj/xAA9EAABAgIFCAgEBAcBAAAAAAABAAIDEQQSEyExBQYXQVFUkaFSYWJxk5TS8AcUIpIyQmOBJDNTcrHB0SP/xAAWAQEBAQAAAAAAAAAAAAAAAAAAAQL/xAAaEQEBAQADAQAAAAAAAAAAAAAAAREhMVEC/9oADAMBAAIRAxEAPwD6nQtmdutM80UaFszt1pfmivv4pi2hqXt99SGudiYbQeoKj4HQtmdutM80UaFszt1pnmitAtX9Hki1f0eSDP8AQtmdulM80UaFszt0pnmitAtX9Hki1f0eSDP9C2Z26UzzRRoWzO3SmeaK0C1f0eSLV/R5IM/0LZm7pS/NFGhbM3dKX5orQLV/R5ItX9Hkgz/QtmbulL805Svv7V/R5KUHn0p2URTqsGgQYlHJb/6ujgGWu6XevSDLvxu4qryQ83lRWO0rLX1Zc4wyp23cUVO27iue2cXloxA1nru/bFWZELtZ5hXUw6p23cUVO27il1jtKXEjVZguIkJlxwA7/wBkQ+r23cVNTtu4rz4dJpPzUevY/LNDbOq51cGU3VrpaxKWK7KztpRJTKnbdxRU7buKXWO0pJpBrFtYAzuE7zdPC6/FGpNdNXtu4oSq7tvMoRF4n4ykxS4ESAqzEzKeu+7u1qkeIWU6RrFphi4TN88ZK8WHaBssWzIuvwIuOo34oOSlPIZEkHOFQmQFad2AH+tamgRmRqNBpDaPFhmI0PqPhFr2zH5hqMpD9kx0I1j9MzOZd1Tw1nUNgU0dpBOOqbZzkcTPYb9pwWZ5G+MNMUAj6X99UrmfFZaOqsM7zg0E6p7dUl2pMVhMw0u+q8mZu1ewrWY4aNGiPfFMSjR4QZGkJu/G0SIcKpwOziu0RXCH/LcTLUMTxVWM+qRxnPG/EkTl3J8JtSG1usATMgP8XK9s5hRc6JCkYJIcLw4i9eblF2UnxaMygwqI+CaRVpYjxHEiFI1qkvzdRXs80gwyDK9x1O29ZwE7ypilVR/TZ9qFf5OjbrC+wIWmDI7f4ivUi1gKtZtWR4lPYGloJNUkYGUwl0gOtBJlYEX3JQMVpmIIB1XKNugshnF44qwY04OB7kttrK8Qyf7iJckxk/zho7nEoJsxtRZjbyU/ROUxPvUyb7KCtn1osxtU/RtHFTJvsoK2Y2osxtVpN9lEm+ygrZjahWk32UICV6mQ2LLdO2au65V8FnrRp2zV3XKvgs9aDUABXddsUyGxZaPjpmrWJ+Vyrf8Aos9anTrmpuuVfBZ60GoyGwIkNiy7TrmpuuVfBZ60H465qbplXwWetBo0ZwD73Sv6ZC6xIgESIOBCyh3xwzXLw4UfKwE5ys2epP07ZqbrlXwWetBp0SVR3cuVtZ9zXEnqiFZy746ZqlpHyuVb/wBFnrSW/HDNgY0fKx6ixvqQajZxOrxD/wAQsx05Zq7plb7W+tCD85oQhAIQhAIQhAIQhAIQhAIQhB//2Q==)
- Neo4j
	Loading data directly from application code with UNWIND
	Cypher®'s UNWIND is what lets an application send a whole batch of records to Neo4j in a single query, instead of one query per re...
- Neo4j
	UNWIND - Cypher Manual
	UNWIND: Transforms lists into individual rows, requiring a new inner value name; lists including nulls; creates distinct sets with...
- Neo4j Online Community
	How to understand UNWIND? - #5 by sylvainroussy - Cypher
	11.02.2022 — UNWIND clause is used to pass from a list to many records (many result lines), opposed to COLLECT() function used to pass from rec...
- GitHub
	Neo4j Batching With UNWIND on get\_node, get\_edge...
	01.04.2025 — By batching these calls using the UNWIND clause, we can drastically reduce the number of queries sent to Neo4j, thereby improving...
- Stack Overflow
	Using Unwind and Dumping Data in neo4j - Query Optimization
	09.07.2020 — First, you want to create a unique constraint on that property: any of them should be unique for each node. Now you can optimize y...
- YouTube·Neo4j
	026 Neo4j Data Loading (ETL/ELT) Best Practices - NODES2022
	05.12.2022 — Relationship creation is a blocking transaction that acquires exclusive locks on the source and target nodes, which can lead to de...
	![](https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSokW68iGIqxVyrOjFyW7R9uKlwi9oG5cHepEFu8E6kA9n9FGJc)
	3m
- Neo4j
	Batching like a pro
	April 13, 2026 — Native runtime memory tracking prevents database downtime from Out-Of-Memory (OOM) crashes. CIT allows auto-completion, keyword hi...
	![](https://encrypted-tbn2.gstatic.com/images?q=tbn:ANd9GcQxtRRjX6-7Ge6oLYs9Ya3-yo9sV6MXUaJlmpJQyh11Y5T3B2cE9Rz3wM46vpElbTnY0DbbhqfszWwtqfWPys_0jYUc0k3XfLpasBoz)
