package io.github.jmallus.guidage.extension

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceTest {

    @Test
    fun `une rafale de valeurs ne fait que quelques rendus, dont le dernier`() = runBlocking {
        // Quarante valeurs en quatre cents millisecondes, comme les flux du Karoo qui arrivent
        // chacun à son heure ; à une par deux cents millisecondes, trois ou quatre passent.
        val rendues = flow {
            repeat(40) {
                emit(it)
                delay(10)
            }
        }.auPlusUneFoisPar(200).toList()

        assertTrue("trop de rendus : ${rendues.size}", rendues.size <= 4)
        assertEquals(0, rendues.first())
        assertEquals(39, rendues.last())
    }
}
