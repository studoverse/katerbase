Mongo Replica Set with Docker Compose
=========================================
A simple sharded Mongo Cluster with a replication factor of 2 running in `docker` using `docker-compose`.

Designed to be quick and simple to get a local or test environment up and running. Needless to say... DON'T USE THIS IN PRODUCTION!

Use this URI in Studio 3T

```
mongodb://server1:27027,server2:27037/?replicaSet=local-rs&readPreference=primary&serverSelectionTimeoutMS=5000&connectTimeoutMS=10000
```

Because we are replicating the Scalingo environment here, we are not using a mongo router. This means that all
replica set members need to use resolvable hostnames (from both the docker container & docker host machine).
Docker containers can resolve each other hostnames because of docker-dns (hostname equals the container name in docker-compose.yml),
in order to resolve these hostnames add the following line to your `/etc/hosts` file on your local machine:

```
127.0.0.1       server1
127.0.0.1       server2
```

Heavily inspired
by [https://github.com/jfollenfant/mongodb-sharding-docker-compose](https://github.com/jfollenfant/mongodb-sharding-docker-compose)

### Mongo Components

* 2 servers:
    * `server1`
    * `server2`
* (TODO): DB data persistence using docker data volumes

### First Run (initial setup)

**Start all of the containers** (daemonized)

```
docker-compose up -d
```

**Initialize the replica set**

```
sh init.sh
```

### Normal Startup

The cluster only has to be initialized on the first run. Subsequent startup can be achieved simply with `docker-compose up`
or `docker-compose up -d`

### Resetting the Cluster

To remove all data and re-initialize the cluster, make sure the containers are stopped and then:

```
docker-compose rm
```

Execute the **First Run** instructions again.