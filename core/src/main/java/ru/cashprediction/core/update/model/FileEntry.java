package ru.cashprediction.core.update.model;

/** Неизменяемая запись управляемого файла; проверку входов выполняет кодек. */
public record FileEntry(String path, long sizeBytes, String sha256, boolean readOnly) { }
