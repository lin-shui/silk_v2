package com.silk.backend.kb

import com.silk.backend.ai.AIConfig
import com.silk.backend.database.DatabaseFactory
import com.silk.backend.models.KBAccessPolicy
import com.silk.backend.models.KBEntry
import com.silk.backend.models.KBEntrySource
import com.silk.backend.models.KBMemoryMetadata
import com.silk.backend.models.KBTopic
import com.silk.backend.models.KBTopicPurpose
import com.silk.backend.models.KnowledgeSpaceType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.time.LocalDateTime
import java.time.ZoneId

@Suppress("UnusedPrivateProperty", "EmptyElseBlock")
class PgKnowledgeBaseRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private val db: Database by lazy {
        DatabaseFactory.getKbPostgresDatabase()
            ?: error("PostgreSQL not initialized.")
    }

    fun listTopics(): List<KBTopic> = transaction(db) {
        val rows = mutableListOf<KBTopic>()
        exec("SELECT * FROM kb_topics") { rs ->
            while (rs.next()) rows.add(rowToTopic(rs))
            @Suppress("UNUSED") Unit
        }
        rows
    }

    fun getTopic(topicId: String): KBTopic? = transaction(db) {
        var result: KBTopic? = null
        exec("SELECT * FROM kb_topics WHERE id = '$topicId'") { rs ->
            if (rs.next()) result = rowToTopic(rs)
            @Suppress("UNUSED") Unit
        }
        result
    }

    fun createTopic(topic: KBTopic): KBTopic = transaction(db) {
        val now = LocalDateTime.now()
        val ap = json.encodeToString(topic.accessPolicy)
        exec("INSERT INTO kb_topics (id,name,project,owner_id,purpose,space_type,group_id,access_policy_json,created_by,updated_by,created_at,updated_at) VALUES ('${topic.id}','${topic.name}','${topic.project}','${topic.ownerId}','${topic.purpose.name}','${topic.spaceType.name}','${topic.groupId}','$ap','${topic.createdBy}','${topic.updatedBy}','$now','$now')")
        topic
    }

    fun updateTopic(topic: KBTopic): KBTopic = transaction(db) {
        val now = LocalDateTime.now()
        val ap = json.encodeToString(topic.accessPolicy)
        exec("UPDATE kb_topics SET name='${topic.name}',project='${topic.project}',owner_id='${topic.ownerId}',purpose='${topic.purpose.name}',space_type='${topic.spaceType.name}',group_id='${topic.groupId}',access_policy_json='$ap',updated_by='${topic.updatedBy}',updated_at='$now' WHERE id='${topic.id}'")
        topic
    }

    fun deleteTopic(topicId: String): Boolean = transaction(db) {
        exec("DELETE FROM kb_embeddings WHERE entry_id IN (SELECT id FROM kb_entries WHERE topic_id='$topicId')")
        exec("DELETE FROM kb_entries WHERE topic_id='$topicId'")
        @Suppress("UNCHECKED_CAST")
        (exec("DELETE FROM kb_topics WHERE id='$topicId'") as Int) > 0
    }

    fun listEntries(topicId: String): List<KBEntry> = transaction(db) {
        val rows = mutableListOf<KBEntry>()
        exec("SELECT * FROM kb_entries WHERE topic_id = '$topicId'") { rs ->
            while (rs.next()) rows.add(rowToEntry(rs))
            @Suppress("UNUSED") Unit
        }
        rows
    }

    fun getEntry(entryId: String): KBEntry? = transaction(db) {
        var result: KBEntry? = null
        exec("SELECT * FROM kb_entries WHERE id = '$entryId'") { rs ->
            if (rs.next()) result = rowToEntry(rs)
            @Suppress("UNUSED") Unit
        }
        result
    }

    fun createEntry(entry: KBEntry): KBEntry = transaction(db) {
        val now = LocalDateTime.now()
        val tags = json.encodeToString(entry.tags)
        val src = json.encodeToString(entry.source)
        val mem = entry.memory?.let { json.encodeToString(it) } ?: "null"
        exec("INSERT INTO kb_entries (id,topic_id,title,content,tags_json,owner_id,status,source_json,memory_json,created_by,updated_by,created_at,updated_at) VALUES ('${entry.id}','${entry.topicId}','${entry.title}','${entry.content}','$tags','${entry.ownerId}','${entry.status.name}','$src','$mem','${entry.createdBy}','${entry.updatedBy}','$now','$now')")
        entry
    }

    fun updateEntry(entry: KBEntry): KBEntry = transaction(db) {
        val now = LocalDateTime.now()
        val tags = json.encodeToString(entry.tags)
        val src = json.encodeToString(entry.source)
        val mem = entry.memory?.let { json.encodeToString(it) } ?: "null"
        exec("UPDATE kb_entries SET topic_id='${entry.topicId}',title='${entry.title}',content='${entry.content}',tags_json='$tags',owner_id='${entry.ownerId}',status='${entry.status.name}',source_json='$src',memory_json='$mem',updated_by='${entry.updatedBy}',updated_at='$now' WHERE id='${entry.id}'")
        entry
    }

    fun deleteEntry(entryId: String): Boolean = transaction(db) {
        exec("DELETE FROM kb_embeddings WHERE entry_id='$entryId'")
        @Suppress("UNCHECKED_CAST")
        (exec("DELETE FROM kb_entries WHERE id='$entryId'") as Int) > 0
    }

    fun allEntries(): List<KBEntry> = transaction(db) {
        val rows = mutableListOf<KBEntry>()
        exec("SELECT * FROM kb_entries") { rs ->
            while (rs.next()) rows.add(rowToEntry(rs))
            @Suppress("UNUSED") Unit
        }
        rows
    }

    fun allTopics(): List<KBTopic> = transaction(db) {
        val rows = mutableListOf<KBTopic>()
        exec("SELECT * FROM kb_topics") { rs ->
            while (rs.next()) rows.add(rowToTopic(rs))
            @Suppress("UNUSED") Unit
        }
        rows
    }

    fun upsertEmbedding(entryId: String, embedding: List<Float>, model: String) = transaction(db) {
        val v = "[" + embedding.joinToString(",") + "]"
        exec("INSERT INTO kb_embeddings (entry_id,embedding,model,updated_at) VALUES ('$entryId','$v'::vector,'$model',NOW()) ON CONFLICT (entry_id) DO UPDATE SET embedding=EXCLUDED.embedding,model=EXCLUDED.model,updated_at=EXCLUDED.updated_at")
    }

    fun deleteEmbedding(entryId: String) = transaction(db) {
        exec("DELETE FROM kb_embeddings WHERE entry_id='$entryId'")
    }

    fun getAllEmbeddings(): Map<String, List<Float>> = transaction(db) {
        val result = mutableMapOf<String, List<Float>>()
        exec("SELECT entry_id, embedding::text FROM kb_embeddings") { rs ->
            while (rs.next()) result[rs.getString("entry_id")] = parseVector(rs.getString("embedding"))
            @Suppress("UNUSED") Unit
        }
        result
    }

    fun vectorSearch(queryEmbedding: List<Float>, limit: Int = 10, topicIds: Set<String>? = null): List<Pair<String, Double>> = transaction(db) {
        val v = "[" + queryEmbedding.joinToString(",") + "]"
        val tf = topicIds?.takeIf { it.isNotEmpty() }?.let { ids -> "AND e.topic_id IN ('" + ids.joinToString("','") { it.replace("'","''") } + "')" } ?: ""
        val sql = "SELECT e.id,1-(emb.embedding<=>'$v'::vector) AS score FROM kb_embeddings emb INNER JOIN kb_entries e ON e.id=emb.entry_id WHERE emb.embedding IS NOT NULL $tf ORDER BY score DESC LIMIT $limit"
        val result = mutableListOf<Pair<String, Double>>()
        exec(sql) { rs ->
            while (rs.next()) result.add(rs.getString("id") to rs.getDouble("score"))
            @Suppress("UNUSED") Unit
        }
        result
    }

    fun hybridSearch(searchTerms: List<String>, queryEmbedding: List<Float>, limit: Int = 10, topicIds: Set<String>? = null, alpha: Double = AIConfig.HYBRID_SEARCH_ALPHA, beta: Double = AIConfig.HYBRID_SEARCH_BETA): List<Pair<String, Double>> = transaction(db) {
        if (queryEmbedding.isEmpty()) return@transaction emptyList()
        val v = "[" + queryEmbedding.joinToString(",") + "]"
        val tf = topicIds?.takeIf { it.isNotEmpty() }?.let { ids -> "AND e.topic_id IN ('" + ids.joinToString("','") { it.replace("'","''") } + "')" } ?: ""
        val kc = searchTerms.joinToString("+") { t ->
            val q = t.replace("'","''")
            "(CASE WHEN LOWER(e.title) LIKE '%${q}%' THEN 10 ELSE 0 END+CASE WHEN LOWER(e.content) LIKE '%${q}%' THEN 3 ELSE 0 END)"
        }
        val kw = searchTerms.joinToString(" OR ") { t ->
            val q = t.replace("'","''")
            "(LOWER(e.title) LIKE '%${q}%' OR LOWER(e.content) LIKE '%${q}%')"
        }
        val sql = "WITH s AS (SELECT e.id,($kc) ks,1-(emb.embedding<=>'$v'::vector) vs FROM kb_embeddings emb INNER JOIN kb_entries e ON e.id=emb.entry_id WHERE emb.embedding IS NOT NULL AND e.status='PUBLISHED' AND ($kw) $tf) SELECT id,($alpha*(ks::float/GREATEST((SELECT MAX(ks) FROM s),1))+$beta*vs) cs FROM s ORDER BY cs DESC LIMIT $limit"
        val result = mutableListOf<Pair<String, Double>>()
        exec(sql) { rs ->
            while (rs.next()) result.add(rs.getString("id") to rs.getDouble("cs"))
            @Suppress("UNUSED") Unit
        }
        result
    }

    fun countEntries(): Long = transaction(db) {
        var n = 0L
        exec("SELECT COUNT(*) FROM kb_entries") { rs -> if (rs.next()) n = rs.getLong(1); @Suppress("UNUSED") Unit }
        n
    }

    fun countTopics(): Long = transaction(db) {
        var n = 0L
        exec("SELECT COUNT(*) FROM kb_topics") { rs -> if (rs.next()) n = rs.getLong(1); @Suppress("UNUSED") Unit }
        n
    }

    private fun rowToTopic(rs: ResultSet): KBTopic {
        val ap = try { json.decodeFromString<KBAccessPolicy>(rs.getString("access_policy_json") ?: "{}") } catch (_: Exception) { KBAccessPolicy() }
        return KBTopic(id=rs.getString("id"), name=rs.getString("name"), project=rs.getString("project")?: "", ownerId=rs.getString("owner_id"),
            purpose=try { KBTopicPurpose.valueOf(rs.getString("purpose")?: "GENERAL") } catch (_: Exception) { KBTopicPurpose.GENERAL },
            spaceType=try { KnowledgeSpaceType.valueOf(rs.getString("space_type")?: "PERSONAL") } catch (_: Exception) { KnowledgeSpaceType.PERSONAL },
            groupId=rs.getString("group_id"), accessPolicy=ap,
            createdBy=rs.getString("created_by")?: "", updatedBy=rs.getString("updated_by")?: "",
            createdAt=toEpoch(rs.getObject("created_at", LocalDateTime::class.java)), updatedAt=toEpoch(rs.getObject("updated_at", LocalDateTime::class.java)))
    }

    private fun rowToEntry(rs: ResultSet): KBEntry {
        val tags = try { json.decodeFromString<List<String>>(rs.getString("tags_json")?: "[]") } catch (_: Exception) { emptyList() }
        val src = try { json.decodeFromString<KBEntrySource>(rs.getString("source_json")?: "{}") } catch (_: Exception) { KBEntrySource() }
        val mem = rs.getString("memory_json")?.takeIf { it.isNotBlank() }?.let { try { json.decodeFromString<KBMemoryMetadata>(it) } catch (_: Exception) { null } }
        return KBEntry(id=rs.getString("id"), topicId=rs.getString("topic_id"), title=rs.getString("title"), content=rs.getString("content")?: "",
            tags=tags, ownerId=rs.getString("owner_id"),
            status=try { KBEntryStatus.valueOf(rs.getString("status")?: "PUBLISHED") } catch (_: Exception) { KBEntryStatus.PUBLISHED },
            source=src, memory=mem, createdBy=rs.getString("created_by")?: "", updatedBy=rs.getString("updated_by")?: "",
            createdAt=toEpoch(rs.getObject("created_at", LocalDateTime::class.java)), updatedAt=toEpoch(rs.getObject("updated_at", LocalDateTime::class.java)))
    }

    companion object {
        fun parseVector(v: String?): List<Float> {
            if (v.isNullOrBlank()) return emptyList()
            return try { v.removeSurrounding("[","]").split(",").map { it.trim().toFloat() } } catch (_: Exception) { emptyList() }
        }
        private fun toEpoch(ldt: LocalDateTime?): Long = ldt?.atZone(ZoneId.of("UTC"))?.toInstant()?.toEpochMilli() ?: 0L
    }
}
