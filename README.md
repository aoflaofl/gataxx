# gataxx

An [Ataxx](https://en.wikipedia.org/wiki/Ataxx) game-playing engine written in Java 21.
It runs at the command line (no GUI) and speaks the Universal Ataxx Interface (UAI),
a UCI-style protocol. Moves are found with a NegaMax search.

## Build and run

```
mvn clean verify
java -jar target/gataxx.jar
```

## Status

Early development. See the project plan for the roadmap.
