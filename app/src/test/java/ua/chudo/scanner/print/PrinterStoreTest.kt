package ua.chudo.scanner.print

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrinterStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun defaultsAndIndependentKindsPersistOnThisDevice() = runBlocking {
        val file = temporary.root.resolve("printers.preferences_pb")
        suspend fun session(check: suspend (PrinterStore) -> Unit) {
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.IO)
            val store = PrinterStore(PreferenceDataStoreFactory.create(scope = scope) { file })
            try { check(store) } finally { scope.cancel(); job.join() }
        }
        session { store ->
            assertEquals("auto", store.preferred(LabelKind.PLAIN).first())
            assertEquals("auto", store.preferred(LabelKind.STICKY).first())
            store.setPreferred(LabelKind.PLAIN, "mfc")
            assertEquals("auto", store.preferred(LabelKind.STICKY).first())
            store.setPreferred(LabelKind.STICKY, "godex")
            assertEquals("mfc", store.preferred(LabelKind.PLAIN).first())
        }
        session { store ->
            assertEquals("mfc", store.preferred(LabelKind.PLAIN).first())
            assertEquals("godex", store.preferred(LabelKind.STICKY).first())
            store.setPreferred(LabelKind.PLAIN, "auto")
            assertEquals("auto", store.preferred(LabelKind.PLAIN).first())
            assertEquals("godex", store.preferred(LabelKind.STICKY).first())
        }
    }
}
