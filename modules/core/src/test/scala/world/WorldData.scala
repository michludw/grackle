// Copyright (c) 2016-2019 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini
package grackle
package world

import cats.data._
import cats.effect.Bracket
import cats.implicits._
import doobie.Transactor
import io.chrisdavenport.log4cats.Logger
import io.circe.{ Json, JsonObject}
import io.circe.literal.JsonStringContext

object WorldData {
  final case class Country(
    code:            String,
    name:            String,
    continent:       String,
    region:          String,
    surfacearea:     Float,
    indepyear:       Option[Short],
    population:      Int,
    lifeexpectancy:  Option[Float],
    gnp:             Option[String],
    gnpold:          Option[String],
    localname:       String,
    governmentform:  String,
    headofstate:     Option[String],
    capitalId:       Option[Int],
    code2:           String
  )

  final case class City(
    id:           Int,
    name:         String,
    countryCode:  String,
    district:     String,
    population:   Int,
  )

  final case class Language(
    countryCode:  String,
    language:     String,
    isOfficial:   Boolean,
    percentage:   Float
  )

  final case class CityCountryLanguage(
    cityName: String,
    countryName: String,
    language: String
  )

  import doobie._
  import doobie.implicits._

  trait CountryRepo[F[_]] {
    def fetchByCode(code: String): F[Option[Country]]
    def fetchAll: F[List[Country]]
    def fetchByCodes(codes: List[String]): F[List[Country]]
    def update(code: String, newName: String): F[Option[Country]]
  }

  object CountryRepo {

    def fromTransactor[F[_]: Logger](xa: Transactor[F])(implicit ev: Bracket[F, Throwable]): CountryRepo[F] =
      new CountryRepo[F] {

        val select: Fragment =
          fr"""
            SELECT code, name, continent, region, surfacearea, indepyear, population,
                   lifeexpectancy, gnp, gnpold, localname, governmentform, headofstate,
                   capital, code2
            FROM   Country
          """

        def fetchByCode(code: String): F[Option[Country]] =
          Logger[F].info(s"CountryRepo.fetchByCode($code)") *>
          (select ++ sql"where code = $code").query[Country].option.transact(xa)

        def fetchByCodes(codes: List[String]): F[List[Country]] =
          NonEmptyList.fromList(codes) match {
            case None      => List.empty[Country].pure[F]
            case Some(nel) =>
              Logger[F].info(s"CountryRepo.fetchByCodes(${codes.length} codes)") *>
              (select ++ fr"where" ++ Fragments.in(fr"code", nel)).query[Country].to[List].transact(xa)
          }

        def fetchAll: F[List[Country]] =
          Logger[F].info(s"CountryRepo.fetchAll") *>
          select.query[Country].to[List].transact(xa)

        def update(code: String, newName: String): F[Option[Country]] =
          Logger[F].info(s"CountryRepo.update") *> {
            sql"UPDATE country SET name = $newName WHERE code = $code".update.run *>
            (select ++ sql"where code = $code").query[Country].option
          } .transact(xa)

      }

  }

  trait CityRepo[F[_]] {
    def fetchAll(pat: Option[String]): F[List[City]]
    def fetchByCountryCode(code: String): F[List[City]]
  }

  object CityRepo {

    def fromTransactor[F[_]: Logger](xa: Transactor[F])(implicit ev: Bracket[F, Throwable]): CityRepo[F] =
      new CityRepo[F] {

        val select: Fragment =
          fr"""
            SELECT id, name, countrycode, district, population
            FROM   city
          """

        def fetchAll(pat: Option[String]): F[List[City]] =
          Logger[F].info(s"CityRepo.fetchByNamePattern($pat)") *>
          (select ++ pat.foldMap(p => sql"WHERE name ILIKE $p")).query[City].to[List].transact(xa)

        def fetchByCountryCode(code: String): F[List[City]] =
          Logger[F].info(s"CityRepo.fetchByCountryCode($code)") *>
          (select ++ sql"WHERE countrycode = $code").query[City].to[List].transact(xa)

      }

  }

  trait LanguageRepo[F[_]] {
    def fetchByCountryCode(code: String): F[List[Language]]
    def fetchByCountryCodes(codes: List[String]): F[Map[String, List[Language]]]
  }

  object LanguageRepo {

    def fromTransactor[F[_]: Logger](xa: Transactor[F])(implicit ev: Bracket[F, Throwable]): LanguageRepo[F] =
      new LanguageRepo[F] {

        val select: Fragment =
          fr"""
            SELECT countrycode, language, isOfficial, percentage
            FROM   countrylanguage
          """

        def fetchByCountryCode(code: String): F[List[Language]] =
          Logger[F].info(s"LanguageRepo.fetchByCountryCode($code)") *>
          (select ++ sql"where countrycode = $code").query[Language].to[List].transact(xa)

        def fetchByCountryCodes(codes: List[String]): F[Map[String, List[Language]]] =
          NonEmptyList.fromList(codes) match {
            case None      => Map.empty[String, List[Language]].pure[F]
            case Some(nel) =>
              Logger[F].info(s"LanguageRepo.fetchByCountryCodes(${codes.length} codes)") *>
              (select ++ fr"where" ++ Fragments.in(fr"countrycode", nel))
                .query[Language]
                .to[List]
                .map { ls =>
                  // Make sure we include empty lists for countries with no languages
                  codes.foldRight(ls.groupBy(_.countryCode)) { (c, m) =>
                    Map(c -> List.empty[Language]) |+| m
                  }
                }
                .transact(xa)
          }

      }

  }

  trait CityCountryLanguageRepo[F[_]] {
    def fetchAll(pat: Option[String]): F[List[CityCountryLanguage]]
  }

  object CityCountryLanguageRepo {
    def fromTransactor[F[_]: Logger](xa: Transactor[F])(implicit ev: Bracket[F, Throwable]): CityCountryLanguageRepo[F] =
      new CityCountryLanguageRepo[F] {

        val select: Fragment =
          fr"""
            SELECT city.name, country.name, countrylanguage.language
            FROM   city, country, countrylanguage
            WHERE  country.code = city.countrycode AND country.code = countrylanguage.countrycode
          """

        def fetchAll(pat: Option[String]): F[List[CityCountryLanguage]] =
          Logger[F].info(s"CityCountryLanguageRepo.fetchAll($pat)") *>
          (select ++ pat.foldMap(p => fr"AND city.name ILIKE $p")).query[CityCountryLanguage].to[List].transact(xa)
      }
  }

  case class Root[F[_]](
    countryRepo: CountryRepo[F],
    cityRepo: CityRepo[F],
    languageRepo: LanguageRepo[F],
    cityCountryLanguageRepo: CityCountryLanguageRepo[F]
  ) {
    def schema = WorldSchema.schema
  }

  def fromTransactor[F[_]: Logger](xa: Transactor[F])(implicit ev: Bracket[F, Throwable]): Root[F] =
    Root(
      CountryRepo.fromTransactor(xa),
      CityRepo.fromTransactor(xa),
      LanguageRepo.fromTransactor(xa),
      CityCountryLanguageRepo.fromTransactor(xa)
    )
}

trait WorldQueryInterpreter[F[_]] extends QueryInterpreter[F, Json] {
  import WorldData._

  import Schema._
  import Query._, Binding._

  implicit val logger: Logger[F]
  implicit val B: Bracket[F, Throwable]
  val xa: Transactor[F]

  def run(q: Query): F[Json] = {
    val root = WorldData.fromTransactor(xa)
    for {
      res <- run(q, root, root.schema.queryType, root, JsonObject.empty)
    } yield Json.obj("data" -> Json.fromJsonObject(res))
  }

  def schemaOfField(schema: Schema, tpe: Type, fieldName: String): Option[Type] = tpe match {
    case NonNullType(tpe) => schemaOfField(schema, tpe, fieldName)
    case ListType(tpe) => schemaOfField(schema, tpe, fieldName)
    case TypeRef(tpnme) => schema.types.find(_.name == tpnme).flatMap(tpe => schemaOfField(schema, tpe, fieldName))
    case ObjectType(_, _, fields, _) => fields.find(_.name == fieldName).map(_.tpe)
    case _ => None
  }

  def run[T](q: Query, root: Root[F], schema: Type, elem: T, acc: JsonObject): F[JsonObject] = {
    println(s"schema: $schema")

    def checkField(fieldName: String): Unit =
      assert(schemaOfField(root.schema, schema, fieldName).isDefined)

    def field(fieldName: String): Type =
      schemaOfField(root.schema, schema, fieldName).get

    (q, elem) match {

      // Optimized queries

      case (CityCountryLanguageJoin(pat), _: Root[F]) =>
        for {
          rows <- root.cityCountryLanguageRepo.fetchAll(pat)
        } yield CityCountryLanguageJoin.add(acc, rows)

      // Root queries

      case (Nest(Select("countries", Nil), q), _: Root[F]) =>
        checkField("countries")
        for {
          countries <- root.countryRepo.fetchAll
          children  <- countries.sortBy(_.name).traverse { country => run(q, root, field("countries"), country, JsonObject.empty) }
        } yield acc.add("countries", Json.fromValues(children.map(Json.fromJsonObject)))

      case (Nest(Select("country", List(StringBinding("code", code))), q), _: Root[F]) =>
        checkField("country")
        for {
          country <- root.countryRepo.fetchByCode(code)
          child   <- country.traverse { city => run(q, root, field("country"), city, JsonObject.empty) }
        } yield acc.add("country", child.map(Json.fromJsonObject).getOrElse(Json.Null))

      case (Nest(Select("cities", List(StringBinding("namePattern", namePattern))), q), _: Root[F]) =>
        checkField("cities")
        for {
          cities   <- root.cityRepo.fetchAll(Some(namePattern))
          children <- cities.sortBy(_.name).traverse { city => run(q, root, field("cities"), city, JsonObject.empty) }
        } yield acc.add("cities", Json.fromValues(children.map(Json.fromJsonObject)))

      // Country queries

      case (Select("name", Nil), country: Country) =>
        checkField("name")
        acc.add("name", Json.fromString(country.name)).pure[F]

      case (Select("code2", Nil), country: Country) =>
        checkField("code2")
        acc.add("code2", Json.fromString(country.code2)).pure[F]

      case (Nest(Select("cities", Nil), q), country: Country) =>
        checkField("cities")
        for {
          cities   <- root.cityRepo.fetchByCountryCode(country.code)
          children <- cities.sortBy(_.name).traverse { city => run(q, root, field("cities"), city, JsonObject.empty) }
        } yield acc.add("cities", Json.fromValues(children.map(Json.fromJsonObject)))

      case (Nest(Select("languages", Nil), q), country: Country) =>
        checkField("languages")
        for {
          languages <- root.languageRepo.fetchByCountryCode(country.code)
          children  <- languages.sortBy(_.language).traverse { language => run(q, root, field("languages"), language, JsonObject.empty) }
        } yield acc.add("languages", Json.fromValues(children.map(Json.fromJsonObject)))

      // City queries

      case (Select("name", Nil), city: City) =>
        checkField("name")
        acc.add("name", Json.fromString(city.name)).pure[F]

      case (Nest(Select("country", Nil), q), city: City) =>
        checkField("country")
        for {
          country <- root.countryRepo.fetchByCode(city.countryCode)
          child   <- country.traverse { country => run(q, root, field("country"), country, JsonObject.empty) }
        } yield acc.add("country", child.map(Json.fromJsonObject).getOrElse(Json.Null))

      // Language queries

      case (Select("language", Nil), language: Language) =>
        checkField("language")
        acc.add("language", Json.fromString(language.language)).pure[F]

      // Generic ...

      case (Group(siblings), elem) =>
        siblings.foldLeftM(acc)((acc, q) => run(q, root, schema, elem, acc))
    }
  }

  object CityCountryLanguageJoin {
    def add(parent: JsonObject, rows: List[CityCountryLanguage]): JsonObject = {
      val cities =
        rows.groupBy(_.cityName).toList.sortBy(_._1).map { case (cityName, rows) =>
          val countryName = rows.head.countryName
          val languages = rows.map(_.language).distinct.sorted.map(language => json"""{ "language": $language }""")

          json"""
            {
              "name": $cityName,
              "country": {
                "name": $countryName,
                "languages": $languages
              }
            }
          """
        }

      parent.add("cities", Json.fromValues(cities))
    }

    def unapply(q: Query): Option[Option[String]] =
      q match {
        case
          Nest(
            Select("cities", bindings),
            Group(
              List(
                Select("name", Nil),
                Nest(
                  Select("country", Nil),
                  Group(
                    List(
                      Select("name", Nil),
                      Nest(
                        Select("languages", Nil),
                        Select("language" ,Nil)
                      )
                    )
                  )
                )
              )
            )
          ) => bindings match {
            case List(StringBinding("namePattern", namePattern)) =>
              Some(Some(namePattern))
            case _ => Some(None)
          }
        case _ => None
      }
  }
}

object WorldQueryInterpreter {
  def fromTransactor[F[_]: Logger](xa0: Transactor[F])(implicit ev: Bracket[F, Throwable]): WorldQueryInterpreter[F] =
    new WorldQueryInterpreter[F] {
      val logger = Logger[F]
      val B = ev
      val xa = xa0
    }
}
