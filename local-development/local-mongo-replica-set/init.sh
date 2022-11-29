#!/bin/bash

docker-compose exec server1 sh -c "mongo --port 27027 < /scripts/init-rs.js"