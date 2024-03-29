package wikipedia

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.rdd.RDD

import scala.io.{BufferedSource, Source}

case class WikipediaArticle(title: String, text: String) {
  /**
    * @return Whether the text of this article mentions `lang` or not
    * @param lang Language to look for (e.g. "Scala")
    */
  def mentionsLanguage(lang: String): Boolean = text.split(' ').contains(lang)
}

object WikipediaRanking {

  val langs = List(
    "JavaScript", "Java", "PHP", "Python", "C#", "C++", "Ruby", "CSS",
    "Objective-C", "Perl", "Scala", "Haskell", "MATLAB", "Clojure", "Groovy")

  val conf: SparkConf = new SparkConf().setMaster("local").setAppName("Wikipedia Ranking")
  val sc: SparkContext = new SparkContext(conf)
  // Hint: use a combination of `sc.textFile`, `WikipediaData.filePath` and `WikipediaData.parse`
  val wikiRdd: RDD[WikipediaArticle] = sc.textFile(WikipediaData.filePath).map(s => WikipediaData.parse(s))

  /** Returns the number of articles on which the language `lang` occurs.
    * Hint1: consider using method `aggregate` on RDD[T].
    * Hint2: consider using method `mentionsLanguage` on `WikipediaArticle`
    */
  def occurrencesOfLang(lang: String, rdd: RDD[WikipediaArticle]): Int = {
    val seqOp: (Int, WikipediaArticle) => Int = (count: Int, article: WikipediaArticle) =>
      if (article.mentionsLanguage(lang)) {
        count + 1
      } else {
        count
      }

    val combOp: (Int, Int) => Int = (x: Int, y: Int) => x + y
    rdd.aggregate(0)(seqOp, combOp)
  }

  /* (1) Use `occurrencesOfLang` to compute the ranking of the languages
   *     (`val langs`) by determining the number of Wikipedia articles that
   *     mention each language at least once. Don't forget to sort the
   *     languages by their occurrence, in decreasing order!
   *
   *   Note: this operation is long-running. It can potentially run for
   *   several seconds.
   */
  def rankLangs(langs: List[String], rdd: RDD[WikipediaArticle]): List[(String, Int)] = {
    for (lang <- langs) yield (lang, occurrencesOfLang(lang, rdd))
  }.sortBy(i => (- i._2,i._1))

  /* Compute an inverted index of the set of articles, mapping each language
   * to the Wikipedia pages in which it occurs.
   */
  def makeIndex(langs: List[String], rdd: RDD[WikipediaArticle]): RDD[(String, Iterable[WikipediaArticle])] =
//    sc.parallelize(langs.map(lang => (lang, rdd.filter(a => a.mentionsLanguage(lang)).collect().toList)))
  rdd.flatMap { a =>
    langs.filter(l =>  a.mentionsLanguage(l)).map(l => l -> a)
  }.groupByKey()
//rdd.map(a => langs.map(l => List(l, (if (a.mentionsLanguage(l))  1 else 0)))
  /* (2) Compute the language ranking again, but now using the inverted index. Can you notice
   *     a performance improvement?
   *
   *   Note: this operation is long-running. It can potentially run for
   *   several seconds.
   */
  def rankLangsUsingIndex(index: RDD[(String, Iterable[WikipediaArticle])]): List[(String, Int)] =
    index.map(i => (i._1,i._2.map(a => 1).count(i => true))).collect().toList.sortBy(i => (- i._2,i._1))
  /* (3) Use `reduceByKey` so that the computation of the index and the ranking are combined.
   *     Can you notice an improvement in performance compared to measuring *both* the computation of the index
   *     and the computation of the ranking? If so, can you think of a reason?
   *
   *   Note: this operation is long-running. It can potentially run for
   *   several seconds.
   */
  def rankLangsReduceByKey(langs: List[String], rdd: RDD[WikipediaArticle]): List[(String, Int)] = rdd.flatMap {
    a => langs.filter(l => a.mentionsLanguage(l)).map(l => l -> 1)
  }.reduceByKey(_ + _).sortBy(- _._2).collect().toList
  // I don't understand what's required of me here

  def rankLangsReduceByKeyCBV(langs: List[String], rdd: RDD[WikipediaArticle]): List[(String, Long)] = rdd.flatMap {
    a => langs.filter(l => a.mentionsLanguage(l))
  }.countByValue().toList.sortBy(- _._2)

  def main(args: Array[String]) {

    /* Languages ranked according to (1) */
    val langsRanked: List[(String, Int)] = timed("Part 1: naive ranking", rankLangs(langs, wikiRdd))

    /* An inverted index mapping languages to wikipedia pages on which they appear */
    def index: RDD[(String, Iterable[WikipediaArticle])] = makeIndex(langs, wikiRdd)

    /* Languages ranked according to (2), using the inverted index */
    val langsRanked2: List[(String, Int)] = timed("Part 2: ranking using inverted index", rankLangsUsingIndex(index))

    /* Languages ranked according to (3) */
    val langsRanked3: List[(String, Int)] = timed("Part 3: ranking using reduceByKey", rankLangsReduceByKey(langs, wikiRdd))

    /* Output the speed of each ranking */
    println(timing)
    sc.stop()
  }

  val timing = new StringBuffer

  def timed[T](label: String, code: => T): T = {
    val start = System.currentTimeMillis()
    val result = code
    val stop = System.currentTimeMillis()
    timing.append(s"Processing $label took ${stop - start} ms.\n")
    result
  }
}
