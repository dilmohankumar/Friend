package com.situationship.app.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.situationship.app.data.model.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * Local message cache. Keyed by clientMessageId so a pending (unsent) message and its server copy are one row.
 * state: "pending" | "failed" | "sent"
 */
@Entity(tableName = "messages", indices = [Index("matchId", "createdAt")])
data class MessageEntity(
    @PrimaryKey val clientMessageId: String,
    val serverId: String?,
    val matchId: String,
    val senderId: String,
    val text: String,
    val createdAt: Long,
    val deliveredAt: Long?,
    val readAt: Long?,
    val state: String,
) {
    companion object {
        fun from(m: ChatMessage) = MessageEntity(
            clientMessageId = m.clientMessageId, serverId = m.id, matchId = m.matchId, senderId = m.senderId,
            text = m.text, createdAt = m.createdAt, deliveredAt = m.deliveredAt, readAt = m.readAt, state = "sent",
        )
    }
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE matchId = :matchId ORDER BY createdAt ASC")
    fun observe(matchId: String): Flow<List<MessageEntity>>

    @Upsert suspend fun upsert(m: MessageEntity)
    @Upsert suspend fun upsertAll(list: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(m: MessageEntity)

    @Query("SELECT * FROM messages WHERE state IN ('pending','failed') ORDER BY createdAt ASC")
    suspend fun unsent(): List<MessageEntity>

    @Query("UPDATE messages SET state = :state WHERE clientMessageId = :cid")
    suspend fun setState(cid: String, state: String)

    @Query("UPDATE messages SET deliveredAt = :at WHERE serverId = :serverId AND deliveredAt IS NULL")
    suspend fun markDelivered(serverId: String, at: Long)

    @Query("UPDATE messages SET readAt = :at, deliveredAt = COALESCE(deliveredAt, :at) WHERE matchId = :matchId AND senderId = :me AND readAt IS NULL AND state = 'sent'")
    suspend fun markReadByOther(matchId: String, me: String, at: Long)

    @Query("DELETE FROM messages WHERE matchId = :matchId")
    suspend fun deleteMatch(matchId: String)

    @Query("DELETE FROM messages") suspend fun clear()
}

@Database(entities = [MessageEntity::class], version = 1, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
}
