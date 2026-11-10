package com.phonoodlesramen.actioncenter

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals

/** Database files made the way Room made them on the TVs and the station, from the schemas it exported. */
internal object SchemaFiles {
    private const val TABLE_NAME = "\${TABLE_NAME}"

    /**
     * Makes [path] from [schema] (under `schemas/`), whose identity hash must be [hash]: its
     * tables, indices, Room's master table and `user_version`. [rows] then runs on it, as
     * the old version would have written them.
     */
    fun create(path: String, schema: String, hash: String, rows: (SQLiteConnection) -> Unit = {}) {
        val database = Json.parseToJsonElement(File("schemas/$schema").readText()).jsonObject.getValue("database").jsonObject
        assertEquals(hash, database.string("identityHash"))
        val connection = BundledSQLiteDriver().open(path)
        try {
            for (entity in database.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.string("tableName")
                connection.execSQL(entity.string("createSql").replace(TABLE_NAME, table))
                entity["indices"]?.jsonArray?.forEach { connection.execSQL(it.jsonObject.string("createSql").replace(TABLE_NAME, table)) }
            }
            database.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = ${database.getValue("version").jsonPrimitive.int}")
            rows(connection)
        } finally {
            connection.close()
        }
    }

    /** The identity hash in [schema]'s file (under `schemas/`). */
    fun hash(schema: String): String =
        Json.parseToJsonElement(File("schemas/$schema").readText()).jsonObject.getValue("database").jsonObject.string("identityHash")

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
