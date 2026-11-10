# The guide station (Phase 8): the JVM service in station/, on a distroless Java 21 base that
# runs as a non-root user and has no shell. Built from :station's installDist, so the image
# holds no build tools:
#   ./gradlew :station:installDist
#   docker build --platform linux/amd64 -t guide-station station
FROM gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d

# The bundled SQLite's library where System.loadLibrary finds it, so the read-only container
# never unpacks it into /tmp.
COPY build/install/station/native/libsqliteJni.so /usr/lib/libsqliteJni.so
COPY build/install/station/lib/ /app/lib/

# /data owned by the non-root user, so a named volume starts out writable. (A bind mount, as in
# compose.yaml, keeps its own folder's owner: see README.md.)
COPY --chown=65532:65532 <<EOF /data/.guide-station
The guide station's volume: guide.db, plex-cache.db, station/ (private: its keys) and approve/.
EOF

ENV STATION_PORT=8443 STATION_DATA=/data
EXPOSE 8443

# `approve <CODE>` runs the same command: docker exec <container> java -cp "/app/lib/*" <main> approve ABC123
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-cp", "/app/lib/*", "com.phonoodlesramen.actioncenter.station.MainKt"]
