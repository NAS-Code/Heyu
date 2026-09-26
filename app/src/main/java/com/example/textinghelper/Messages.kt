package com.example.textinghelper

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils

data class ContactStats(
    val contactId: Long,
    val name: String,
    val phone: String, // normalized number we actually text them on
    val count: Int,
    val count12Months: Int,
    val lastDate: Long,
    val lastOutgoing: Long?, // null = I've never texted them (in readable data)
    val lastFromMe: Boolean,
    val threadIds: List<Long> = emptyList(), // 1:1 threads with this contact (usually one)
) : java.io.Serializable

data class Diagnostic(
    val smsRows: Int,
    val mmsRows: Int,
    val oldest: Long?,
    val last30Days: Int,
    val oneToOneThreads: Int,
    val groupThreads: Int,
    val emptyThreads: Int, // threads that exist but have no readable SMS/MMS; possible RCS
    val unmatched: List<Pair<String, Int>>, // numbers not in contacts, with message counts
    val contacts: List<ContactStats>,
) : java.io.Serializable

// ponytail: assumes US numbers; pass a different country code if you text abroad a lot
fun normalize(raw: String): String =
    PhoneNumberUtils.formatNumberToE164(raw, "US")
        ?: raw.filter { it.isDigit() || it == '+' }.ifEmpty { raw.trim().lowercase() }

// Last read, saved so the app can show it instantly on startup while a fresh read runs in the background.
// ponytail: Java serialization; if these classes change shape, the old file fails to load and is just re-read.
private fun Context.cacheFile() = java.io.File(filesDir, "diagnostic.bin")

fun loadCachedDiagnostic(ctx: Context): Diagnostic? = try {
    java.io.ObjectInputStream(ctx.cacheFile().inputStream()).use { it.readObject() as Diagnostic }
} catch (e: Exception) {
    null
}

/** Reads all messages (a few seconds) and saves the result for next startup. */
fun readAndCacheDiagnostic(ctx: Context): Diagnostic = readDiagnostic(ctx).also { d ->
    try { java.io.ObjectOutputStream(ctx.cacheFile().outputStream()).use { it.writeObject(d) } } catch (_: Exception) {}
}

private fun Context.query(uri: String, cols: Array<String>, where: String? = null, sort: String? = null, each: (Cursor) -> Unit) {
    contentResolver.query(Uri.parse(uri), cols, where, null, sort)?.use { c -> while (c.moveToNext()) each(c) }
}

private class Msg(val threadId: Long, val date: Long, val fromMe: Boolean)

fun readDiagnostic(ctx: Context): Diagnostic {
    // Canonical addresses: id -> phone number. Threads reference these by id.
    val canonical = HashMap<String, String>()
    ctx.query("content://mms-sms/canonical-addresses", arrayOf("_id", "address")) {
        canonical[it.getString(0)] = it.getString(1) ?: ""
    }

    // Thread id -> the other person's number, or null if it's a group chat.
    val threadAddr = HashMap<Long, String?>()
    ctx.query("content://mms-sms/conversations?simple=true", arrayOf("_id", "recipient_ids")) {
        val ids = (it.getString(1) ?: "").split(" ").filter(String::isNotBlank)
        // RCS group chats show up as ONE fake address like "abc...==@rcs.google.com", so anything
        // that isn't a phone number counts as a group too.
        val addr = if (ids.size == 1) canonical[ids[0]] else null
        threadAddr[it.getLong(0)] = addr?.takeUnless { '@' in it }
    }

    val msgs = ArrayList<Msg>()
    var smsRows = 0
    var mmsRows = 0
    // SMS type: 1 = received, 2 = sent (skip drafts/failed/queued). date is in milliseconds.
    ctx.query("content://sms", arrayOf("thread_id", "date", "type"), "type IN (1,2)") {
        smsRows++
        msgs += Msg(it.getLong(0), it.getLong(1), it.getInt(2) == 2)
    }
    // MMS msg_box: 1 = received, 2 = sent. date is in SECONDS.
    ctx.query("content://mms", arrayOf("thread_id", "date", "msg_box"), "msg_box IN (1,2)") {
        mmsRows++
        msgs += Msg(it.getLong(0), it.getLong(1) * 1000, it.getInt(2) == 2)
    }

    // Normalized number -> (contact id, name). A contact with 2 numbers maps both to one id.
    val contactByNumber = HashMap<String, Pair<Long, String>>()
    ctx.query(Phone.CONTENT_URI.toString(), arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER)) {
        val num = it.getString(2) ?: return@query
        contactByNumber[normalize(num)] = it.getLong(0) to (it.getString(1) ?: num)
    }

    class Acc(val name: String, val phone: String) {
        val threads = LinkedHashSet<Long>()
        var count = 0; var count12 = 0; var last = 0L; var lastOut: Long? = null; var lastFromMe = false
    }
    val byContact = HashMap<Long, Acc>()
    val unmatched = HashMap<String, Int>()
    val threadsWithMsgs = HashSet<Long>()
    val now = System.currentTimeMillis()
    val cutoff30 = now - 30L * 86_400_000
    val cutoff12Months = now - 365L * 86_400_000

    for (m in msgs) {
        threadsWithMsgs += m.threadId
        val addr = threadAddr[m.threadId] ?: continue // group chat or unknown thread
        val num = normalize(addr)
        val (id, name) = contactByNumber[num] ?: run { unmatched.merge(num, 1, Int::plus); null } ?: continue
        val a = byContact.getOrPut(id) { Acc(name, num) }
        a.count++
        a.threads += m.threadId
        if (m.date >= cutoff12Months) a.count12++
        if (m.date > a.last) { a.last = m.date; a.lastFromMe = m.fromMe }
        if (m.fromMe && m.date > (a.lastOut ?: 0)) a.lastOut = m.date
    }

    return Diagnostic(
        smsRows = smsRows,
        mmsRows = mmsRows,
        oldest = msgs.minOfOrNull { it.date },
        last30Days = msgs.count { it.date >= cutoff30 },
        oneToOneThreads = threadAddr.values.count { it != null },
        groupThreads = threadAddr.values.count { it == null },
        emptyThreads = threadAddr.keys.count { it !in threadsWithMsgs },
        unmatched = unmatched.toList().sortedByDescending { it.second },
        contacts = byContact.map { (id, a) -> ContactStats(id, a.name, a.phone, a.count, a.count12, a.last, a.lastOut, a.lastFromMe, a.threads.toList()) }
            .sortedByDescending { it.count12Months },
    )
}

data class Attachment(val uri: String, val isVideo: Boolean)

data class Line(val date: Long, val fromMe: Boolean, val text: String, val attachments: List<Attachment> = emptyList())

/** The last [limit] messages (with text) across these 1:1 threads, oldest first. [mineOnly]: only ones I sent. */
fun readConversation(ctx: Context, threadIds: List<Long>, limit: Int = 50, mineOnly: Boolean = false): List<Line> {
    if (threadIds.isEmpty()) return emptyList()
    val threads = threadIds.joinToString(",")
    val out = ArrayList<Line>()
    ctx.query("content://sms", arrayOf("date", "type", "body"),
        "thread_id IN ($threads) AND type IN (${if (mineOnly) "2" else "1,2"})", "date DESC LIMIT $limit") {
        out += Line(it.getLong(0), it.getInt(1) == 2, it.getString(2) ?: "")
    }

    // MMS/RCS: the message row has no text; text and attachments live in the "part" table.
    class Mms(val date: Long, val fromMe: Boolean)
    val mms = LinkedHashMap<Long, Mms>()
    ctx.query("content://mms", arrayOf("_id", "date", "msg_box"),
        "thread_id IN ($threads) AND msg_box IN (${if (mineOnly) "2" else "1,2"})", "date DESC LIMIT $limit") {
        mms[it.getLong(0)] = Mms(it.getLong(1) * 1000, it.getInt(2) == 2)
    }
    if (mms.isNotEmpty()) {
        val parts = HashMap<Long, MutableList<String>>()
        val media = HashMap<Long, MutableList<Attachment>>()
        ctx.query("content://mms/part", arrayOf("mid", "ct", "text", "_id"), "mid IN (${mms.keys.joinToString(",")})") {
            val ct = it.getString(1) ?: ""
            if (ct.startsWith("image/") || ct.startsWith("video/"))
                media.getOrPut(it.getLong(0)) { mutableListOf() } += Attachment("content://mms/part/${it.getLong(3)}", ct.startsWith("video/"))
            val piece = when {
                ct == "text/plain" -> it.getString(2)
                ct.startsWith("image/") -> "[photo]"
                ct.startsWith("video/") -> "[video]"
                ct.startsWith("audio/") -> "[voice message]"
                ct == "application/smil" -> null // layout info, not content
                else -> "[attachment]"
            }
            if (!piece.isNullOrBlank()) parts.getOrPut(it.getLong(0)) { mutableListOf() } += piece
        }
        for ((id, m) in mms) parts[id]?.let { out += Line(m.date, m.fromMe, it.joinToString(" "), media[id].orEmpty()) }
    }
    return out.sortedBy { it.date }.takeLast(limit)
}
