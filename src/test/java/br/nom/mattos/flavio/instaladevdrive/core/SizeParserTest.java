package br.nom.mattos.flavio.instaladevdrive.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SizeParser alimenta diretamente o "maximum=" do script do DISKPART; um
 * erro de parsing aqui gera um comando de criacao de disco com o tamanho
 * errado sem que o usuario perceba antes de o script ja ter rodado.
 *
 * @author flavio mattos
 */
class SizeParserTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void parseiaUnidadesBasicas() {
        assertEquals(50 * GB, SizeParser.parseToBytes("50GB"));
        assertEquals(100 * GB, SizeParser.parseToBytes("100GB"));
        assertEquals(1024 * GB, SizeParser.parseToBytes("1TB"));
        assertEquals(512L * 1024 * 1024, SizeParser.parseToBytes("512MB"));
        assertEquals(2048L, SizeParser.parseToBytes("2KB"));
    }

    @Test
    void unidadeAusenteAssumeGB() {
        assertEquals(50 * GB, SizeParser.parseToBytes("50"));
    }

    @Test
    void aceitaEspacosEMinusculas() {
        assertEquals(50 * GB, SizeParser.parseToBytes(" 50 gb "));
        assertEquals(50 * GB, SizeParser.parseToBytes("50gb"));
    }

    @Test
    void aceitaVirgulaComoSeparadorDecimal() {
        assertEquals(Math.round(1.5 * 1024 * GB), SizeParser.parseToBytes("1,5TB"));
        assertEquals(Math.round(1.5 * 1024 * GB), SizeParser.parseToBytes("1.5TB"));
    }

    @Test
    void rejeitaTextoNaoNumerico() {
        assertThrows(IllegalArgumentException.class, () -> SizeParser.parseToBytes("abc"));
    }

    @Test
    void rejeitaUnidadeDesconhecida() {
        assertThrows(IllegalArgumentException.class, () -> SizeParser.parseToBytes("50PB"));
    }

    @Test
    void rejeitaValorNegativo() {
        // O regex nao aceita sinal de menos; garante que nao vira, p.ex., um
        // tamanho negativo silenciosamente aceito e repassado ao DISKPART.
        assertThrows(IllegalArgumentException.class, () -> SizeParser.parseToBytes("-50GB"));
    }

    @Test
    void rejeitaStringVazia() {
        assertThrows(IllegalArgumentException.class, () -> SizeParser.parseToBytes(""));
    }

    @Test
    void validateMinimumAceitaExatamente50Gb() {
        SizeParser.validateMinimum(SizeParser.MINIMUM_BYTES);
    }

    @Test
    void validateMinimumRejeitaUmByteAbaixoDoMinimo() {
        assertThrows(IllegalArgumentException.class, () -> SizeParser.validateMinimum(SizeParser.MINIMUM_BYTES - 1));
    }

    @Test
    void toHumanReadableFormataComDuasCasasDecimais() {
        // Normaliza separador decimal (String.format usa o locale padrao da
        // JVM, que pode usar ',' em vez de '.') para o teste nao depender do
        // locale da maquina onde roda.
        assertEquals("50.00 GB", SizeParser.toHumanReadable(50 * GB).replace(',', '.'));
        assertEquals("1.50 GB", SizeParser.toHumanReadable(Math.round(1.5 * GB)).replace(',', '.'));
    }
}
