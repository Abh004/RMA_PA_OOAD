#!/usr/bin/env python3
import re
import sys

STOPWORDS = {
    "a",
    "an",
    "the",
    "and",
    "or",
    "but",
    "if",
    "then",
    "else",
    "when",
    "at",
    "by",
    "for",
    "from",
    "in",
    "into",
    "on",
    "onto",
    "of",
    "off",
    "over",
    "under",
    "to",
    "with",
    "without",
    "as",
    "is",
    "are",
    "was",
    "were",
    "be",
    "been",
    "being",
    "it",
    "this",
    "that",
    "these",
    "those",
    "i",
    "me",
    "my",
    "we",
    "our",
    "you",
    "your",
    "he",
    "she",
    "they",
    "them",
    "his",
    "her",
    "their",
    "not",
    "no",
    "nor",
    "so",
    "too",
    "very",
    "just",
    "also",
    "can",
    "could",
    "should",
    "would",
    "will",
    "have",
    "has",
    "had",
    "do",
    "does",
    "did",
}


def tokenize(text):
    return re.findall(r"[a-zA-Z][a-zA-Z']+", text.lower())


def build_phrases(tokens):
    phrases = []
    current = []
    for t in tokens:
        if t in STOPWORDS:
            if current:
                phrases.append(current)
                current = []
        else:
            current.append(t)
    if current:
        phrases.append(current)
    return phrases


def rake_keywords(text, max_phrases=5):
    tokens = tokenize(text)
    phrases = build_phrases(tokens)

    word_freq = {}
    word_degree = {}
    for phrase in phrases:
        length = len(phrase)
        degree = length - 1
        for word in phrase:
            word_freq[word] = word_freq.get(word, 0) + 1
            word_degree[word] = word_degree.get(word, 0) + degree

    word_score = {}
    for word in word_freq:
        word_score[word] = (word_degree[word] + word_freq[word]) / word_freq[word]

    phrase_scores = []
    for phrase in phrases:
        score = sum(word_score.get(word, 0) for word in phrase)
        phrase_scores.append((" ".join(phrase), score))

    phrase_scores.sort(key=lambda x: x[1], reverse=True)
    top_phrases = [p for p, _ in phrase_scores[:max_phrases]]
    return [p.replace(" ", "_") for p in top_phrases if p.strip()]


def detect_sentiment(text):
    try:
        from vaderSentiment.vaderSentiment import SentimentIntensityAnalyzer

        analyzer = SentimentIntensityAnalyzer()
        compound = analyzer.polarity_scores(text)["compound"]
        if compound >= 0.05:
            return "Positive"
        if compound <= -0.05:
            return "Negative"
        return "Neutral"
    except Exception:
        pass

    try:
        from textblob import TextBlob

        polarity = TextBlob(text).sentiment.polarity
        if polarity >= 0.1:
            return "Positive"
        if polarity <= -0.1:
            return "Negative"
        return "Neutral"
    except Exception:
        pass

    # Fallback: simple heuristic on punctuation/emphasis
    exclam = text.count("!")
    if exclam >= 2:
        return "Negative"
    return "Neutral"


def main():
    raw = sys.stdin.read().strip()
    if not raw:
        raw = " ".join(sys.argv[1:]).strip()
    text = raw.strip()

    sentiment = detect_sentiment(text)
    keywords = rake_keywords(text)

    print(sentiment)
    print(",".join(keywords))


if __name__ == "__main__":
    main()
