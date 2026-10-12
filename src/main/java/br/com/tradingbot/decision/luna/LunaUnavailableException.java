package br.com.tradingbot.decision.luna;

/** Erro de integracao: nao retorna uma decisao operacional inventada. */
public final class LunaUnavailableException extends RuntimeException {
    public LunaUnavailableException(String message) { super(message); }
    public LunaUnavailableException(String message, Throwable cause) { super(message, cause); }
}
