package br.com.quack.pdv.plataforma.sincronizacao.protocolo;

/** Tipo de um valor no fio (D4): todo valor viaja como texto (ou nulo) e o tipo diz como ler de volta. */
public enum TipoColuna { TEXTO, INTEIRO, DECIMAL, REAL, BOOLEANO, DATA, HORA, DATAHORA, DATAHORA_OFFSET, BINARIO }
