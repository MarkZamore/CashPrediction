package ru.cashprediction.core.update.model;

/** Идентичность установленной версии, проверяемая по фактическому дереву. */
public record InstalledVersion(int releaseNumber, String commitSha, String treeSha256) { }
