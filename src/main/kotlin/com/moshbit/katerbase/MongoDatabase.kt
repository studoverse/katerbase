package com.moshbit.katerbase

import com.mongodb.*
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import java.util.concurrent.TimeUnit

abstract class MongoDatabase(uri: String, allowReadFromSecondaries: Boolean) {

  companion object {
    private val ConnectionString.isMultiNodeSetup get() = hosts.size > 1 // true if replication or sharding is in place

    fun createMongoClientFromUri(connectionString: ConnectionString, allowReadFromSecondaries: Boolean): MongoClient {
      val clientSettings = MongoClientSettings.builder()
        .applyConnectionString(connectionString)
        .apply {
          readConcern(ReadConcern.LOCAL)
          when {
            connectionString.isMultiNodeSetup && allowReadFromSecondaries -> {
              // Only allow read from secondaries if it is a multi node setup
              // Set maxStalenessSeconds, see https://docs.mongodb.com/manual/core/read-preference/#maxstalenessseconds
              readPreference(ReadPreference.secondaryPreferred(90L, TimeUnit.SECONDS))
              writeConcern(WriteConcern.MAJORITY)
            }
            connectionString.isMultiNodeSetup -> {
              readPreference(ReadPreference.primaryPreferred())
              writeConcern(WriteConcern.W1)
            }
            else -> {
              readPreference(ReadPreference.primary())
              writeConcern(WriteConcern.W1)
            }
          }
        }
        .build()

      return MongoClients.create(clientSettings)
    }
  }
}