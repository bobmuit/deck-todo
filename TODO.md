# TODO

## Release 0.3.0 (volgorde en vastpinnen)

- [ ] Tag `v0.3.0` op `main` zetten en pushen; `release.yml` publiceert dan "Vinkje 0.3.0" voor Obtainium:

  ```sh
  git fetch origin
  git tag -a v0.3.0 origin/main -F - <<'MSG'
  Volgorde en vastpinnen

  - "Volgorde aanpassen" in het menu van het startscherm: groepen en lijsten verslepen. De volgorde van de lijsten in een groep gaat naar Deck; de rest blijft op deze telefoon.
  - Lijsten vastpinnen bovenaan het startscherm, onder de slimme lijsten (ook via het menu van een lijst).
  - Importer: netwerkfouten worden opnieuw geprobeerd; importCompleted kan per lijst.
  MSG
  git push origin v0.3.0
  ```

- [ ] Controleren dat de release-run slaagt en `vinkje-0.3.0.apk` aan de release hangt.

## Volgorde aanpassen uitproberen (emulator of telefoon)

De logica (`domain/HomeLayout.kt`) is getest, het slepen zelf nog niet.

- [ ] Losse lijst omlaag langs een uitgeklapte groep: schuift pas door bij de laatste lijst van die groep en springt niet heen en weer.
- [ ] Groep omhoog langs een andere groep: schuift door bij de groepskop; zijn eigen lijsten zijn tijdens het slepen ingeklapt.
- [ ] Lijsten binnen een groep verslepen; in een groep zonder beheerrechten kan dat niet (geen sleepgreep).
- [ ] Slepen voorbij de rand van het scherm: de lijst scrolt mee en de rij blijft onder de vinger.
- [ ] Vastpinnen en losmaken, zowel in "Volgorde aanpassen" als via het menu van een lijst; de volgorde van de vastgepinde lijsten verslepen.
- [ ] "Klaar" en de terugknop slaan op; een ongewijzigde volgorde stuurt niets naar Deck.
- [ ] De volgorde binnen een gedeelde groep komt aan op de andere telefoon en in Deck web.
- [ ] Update vanaf 0.2.0: de databasemigratie 4 → 5 werkt, bestaande lijsten en groepen staan nog in de oude volgorde.
- [ ] Release-build (`./gradlew :app:installStaging`): R8 laat het slepen heel.
