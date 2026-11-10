package com.phonoodlesramen.actioncenter.safety

class InMemoryCrashStore : CrashStore {
    private var record: CrashRecord? = null

    override fun read(): CrashRecord? = record

    override fun write(record: CrashRecord) {
        this.record = record
    }

    override fun clear() {
        record = null
    }
}
