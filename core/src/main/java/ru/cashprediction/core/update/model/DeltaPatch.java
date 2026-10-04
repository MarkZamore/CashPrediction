package ru.cashprediction.core.update.model;

/** Неизменяемый дескриптор прямой дельты от одной установленной базы. */
public record DeltaPatch(int baseReleaseNumber, String baseCommitSha, String baseTreeSha256,
                         String assetName, long sizeBytes, String sha256) { }
