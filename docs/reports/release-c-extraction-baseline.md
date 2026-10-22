# Release C extraction evaluation — BASELINE before any parser change (main at 06e4126)

285 lines. A line is *right* when name, amount and unit match the label (a container whose size is the label's amount counts).

## Field accuracy, by group

| group | lines | name | quantity | unit | catalogue match | aisle | optional | garnish | A or B | shown amount | right |
|---|---|---|---|---|---|---|---|---|---|---|---|
| blog | 29 | 72% | 100% | 100% | 72% | 100% | 100% | 100% | 83% | 93% | 72% |
| eu | 38 | 47% | 55% | 55% | 47% | 89% | 97% | 100% | 95% | 53% | 47% |
| synthetic-paste | 18 | 56% | 72% | 83% | 56% | 78% | 100% | 100% | 100% | 72% | 56% |
| uk | 121 | 80% | 96% | 91% | 79% | 97% | 100% | 100% | 98% | 91% | 78% |
| us | 79 | 81% | 100% | 97% | 81% | 96% | 99% | 95% | 96% | 77% | 80% |
| all | 285 | 74% | 91% | 88% | 73% | 95% | 99% | 99% | 95% | 81% | 72% |

## The gate

| | right | wrong |
|---|---|---|
| accepted (read offline) | 196 | **47** incorrectly accepted |
| flagged (a look, or Claude) | 10 incorrectly flagged | 32 caught |

Flag rate 15%; of the wrong readings, 41% are flagged.

## Incorrectly accepted

| id | line | got (name / amount / shown) | want | wrong |
|---|---|---|---|---|
| rc-005 | 8 boneless, skinless chicken breasts cut into 2.5cm cubes | boneless skinless chicken breasts cut / 8  / 8 | boneless skinless chicken breast / 8.0  / 8 | name, catalogue |
| rc-012 | 1 celery stick, finely chopped | celery stick / 1  / 1 | celery / 1.0 stick / 1 stick | unit, shown |
| rc-017 | 500g pack fresh egg lasagne sheets | egg lasagne sheets / 1 packet / 1 packet | fresh egg lasagne sheets / 1.0 packet / 1 packet | name, catalogue |
| rc-019 | 125g ball mozzarella roughly torn | ball mozzarella / 125 g / 130g | mozzarella / 125.0 g / 130g | name, catalogue |
| rc-021 | large handful basil leaves torn (optional) | basil leaves /   / (no amount) | basil / 1.0 other / 1 | quantity, unit, shown |
| rc-029 | 2 celery sticks finely chopped | celery sticks / 2  / 2 | celery / 2.0 stick / 2 sticks | unit, shown |
| rc-034 | 900g beef for braising such as skirt, buy a whole piece and cut it yourself for bigger chunks or buy ready-diced | beef / 900 g / 900g | braising beef / 900.0 g / 900g | name, catalogue |
| rc-045 | 2 large egg whites | large egg whites / 2  / 2 | egg / 2.0  / 2 | name, catalogue |
| rc-070 | 5cm piece of ginger | cm piece of ginger / 5  / 5 | ginger / 1.0 piece / 1 piece | name, quantity, unit, catalogue, shown |
| rc-071 | ½ a bunch of fresh coriander | a bunch of fresh coriander / 1/2  / ½ | coriander / 0.5 bunch / ½ bunch | name, unit, catalogue, shown |
| rc-088 | 500g tomato passata | tomato passata / 500 g / 500g | passata / 500.0 g / 500g | name, catalogue |
| rc-089 | 200g mature Cheddar cheese, grated | mature cheddar cheese / 200 g / 200g | mature cheddar / 200.0 g / 200g | name, catalogue |
| rc-090 | ½ a round lettuce | a round lettuce / 1/2  / ½ | round lettuce / 0.5  / ½ | name, catalogue |
| rc-101 | 2 fat cloves garlic, peeled and grated into a paste | fat cloves garlic / 2  / 2 | garlic / 2.0 clove / 2 cloves | name, unit, catalogue, shown |
| rc-104 | 1½ tsp paprika powder, for  colour (optional) | paprika powder / 3/2 tsp / 1½tsp | paprika / 1.5 tsp / 1½tsp | name, catalogue |
| rc-108 | 1 black cardamom pod | black cardamom pod / 1  / 1 | black cardamom / 1.0  / 1 | name, catalogue, aisle |
| rc-109 | 6 green cardamom pods (optional) | green cardamom pods / 6  / 6 | green cardamom / 6.0  / 6 | name, catalogue |
| rc-110 | 2cm cinnamon stick | cm cinnamon stick / 2  / 2 | cinnamon / 1.0 stick / 1 stick | name, quantity, unit, catalogue, shown |
| rc-113 | 2-4 small green chillies,  whole but pierced | green chillies / 2-4  / 4 | green chilli / 2.0-4.0  / 4 | name, catalogue |
| rc-115 | 1 rounded tsp dried fenugreek leaves, crushed to a  powder (optional) | rounded tsp dried fenugreek leaves / 1  / 1 | dried fenugreek leaves / 1.0 tsp / 1tsp | name, unit, catalogue, shown |
| rc-122 | 3 cups (360g) King Arthur Unbleached All-Purpose Flour | king arthur unbleached all-purpose flour / 3 cup / 360g | plain flour / 3.0 cup / 380g | name, catalogue, shown |
| rc-131 | 2 teaspoons King Arthur Pure Vanilla Extract | king arthur pure vanilla extract / 2 tsp / 2tsp | vanilla extract / 2.0 tsp / 2tsp | name, catalogue |
| rc-141 | 2 large egg yolks | large egg yolks / 2  / 2 | egg / 2.0  / 2 | name, catalogue |
| rc-152 | 1 1/2 cups all purpose flour (6.75 ounces) | all purpose flour / 3/2 cup / 360ml | plain flour / 1.5 cup / 190g | name, catalogue, shown |
| rc-162 | 1 pound small white boiling potatoes ((baby yukons), cut in half) | white boiling potatoes / 1 lb / 460g | white potato / 1.0 lb / 460g | name, catalogue |
| rc-170 | Toppings of choice: shredded cheddar cheese, avocado, shredded lettuce | toppings of choice /   / (no amount) | cheddar /   / (no amount) | name, catalogue, aisle, optional, garnish |
| rc-173 | 2 (14.5-oz.) cans whole tomatoes | tomatoes / 2 tin / 2 tins | whole tomatoes / 2.0 tin / 2 tins | name, catalogue |
| rc-179 | 1 (1o-oz.) package lasagna noodles | package lasagna noodles / 1  / 1 | lasagne sheets / 1.0 packet / 1 packet | name, unit, catalogue, shown |
| rc-184 | 10 ounces canned Rotel mild tomatoes with green chilies | canned rotel mild tomatoes / 10 oz / 290g | tinned tomatoes with green chillies / 10.0 oz / 290g | name, catalogue |
| rc-185 | 1/2 cup canned tomato sauce | canned tomato sauce / 1/2 cup / 120ml | tinned tomato sauce / 0.5 cup / 120ml | name, catalogue |
| rc-188 | 4 (6-oz.) tilapia fillets | tilapia / 4 fillet / 4 fillets | tilapia fillet / 4.0  / 4 | unit, shown |
| rc-221 | 4 tsp instant coffee granules | instant coffee granules / 4 tsp / 4tsp | instant coffee / 4.0 tsp / 4tsp | name, catalogue |
| rc-223 | 2 dl cracked rye kernels | dl cracked rye kernels / 2  / 2 | cracked rye / 200.0 ml / 200ml | name, quantity, unit, catalogue, shown |
| rc-224 | 2 dl flax seed/linseeds | dl flax seed linseeds / 2  / 2 | linseed / 200.0 ml / 130g | name, quantity, unit, catalogue, shown |
| rc-226 | 4 dl sour dough | dl sour dough / 4  / 4 | sourdough starter / 400.0 ml / 400ml | name, quantity, unit, catalogue, shown |
| rc-227 | 8 dl water | dl water / 8  / 8 | water / 800.0 ml / 800ml | name, quantity, unit, catalogue, shown |
| rc-233 | 10 whole black peppercorns (, cracked) | black peppercorns / 10  / 10 | black peppercorn / 10.0  / 10 | name, catalogue, aisle |
| rc-239 | 600g/ 1.2lb   chicken thigh ((boneless, skinless), cut into 3cm / 2.2" cubes (Note 1)) | chicken thigh / 600 g / 600g | boneless skinless chicken thigh / 600.0 g / 600g | name, catalogue |
| rc-242 | 1 tsp each salt, cumin, ground coriander, paprika (sweet, normal or smokey) | each salt / 1 tsp / 1tsp | salt / 1.0 tsp / 1tsp | name, catalogue |
| rc-248 | 1 2/3 cups (400ml)  tomato passata ((tomato puree)) | tomato passata / 5/3 cup / 400ml | passata / 1.6667 cup / 420ml | name, catalogue, shown |
| rc-253 | 1 teaspoon red chili powder (recommend Kashmiri) | red chili powder / 1 tsp / 1tsp | chilli powder / 1.0 tsp / 1tsp | name, catalogue |
| rc-260 | 1 cup long- or short-grain brown rice* ((rinsed thoroughly in a fine mesh strainer)) | long-or short-grain brown rice * / 1 cup / 250ml | brown rice / 1.0 cup / 200g | name, catalogue, shown |
| rc-265 | 2-3 Tbsp organic brown sugar, muscovado sugar, or maple syrup | organic brown sugar / 2-3 tbsp / 3tbsp | brown sugar / 2.0-3.0 tbsp / 3tbsp | name, catalogue, A or B |
| rc-266 | 1-2 tsp chili garlic sauce  ((more or less depending on preferred spice)) | chili garlic sauce / 1-2 tsp / 2tsp | chilli garlic sauce / 1.0-2.0 tsp / 2tsp | name, catalogue |
| rc-271 | loo roll | loo roll /   / (no amount) | toilet roll /   / (no amount) | name, catalogue |
| rc-277 | cheddar - big block | cheddar-block /   / (no amount) | cheddar /   / (no amount) | name, catalogue, aisle |
| rc-280 | 3 x peppers | peppers / 3  / 3 | pepper / 3.0  / 3 | name, catalogue, aisle |

## Accepted and right, but shown or filed differently

| id | line | got | want | wrong |
|---|---|---|---|---|
| rc-054 | 400g/14oz dried spaghetti (or pasta shape of your choice | dried spaghetti / 400 g / 400g | dried spaghetti / 400.0 g / 400g | A or B |
| rc-068 | 4 skinless free-range chicken breasts | skinless free-range chicken breasts / 4  / 4 | skinless free-range chicken breast / 4.0  / 4 | catalogue |
| rc-114 | 75ml single cream,  or to taste | single cream / 75 ml / 75ml | single cream / 75.0 ml / 75ml | A or B |
| rc-123 | 1/2 cup (113g) milk, (skim, 1%, 2% or whole, your choice)* | milk / 1/2 cup / 120g | milk / 0.5 cup / 120ml | shown |
| rc-124 | 1/2 to 2/3 cup (113g to 152g) hot water, enough to make a soft, smooth dough* (see "tips," below) | hot water / 1/2-2/3 cup / 160g | water / 0.5-0.6667 cup / 170ml | shown |
| rc-126 | 1 1/4 teaspoons (8g) table salt | table salt / 5/4 tsp / 8g | salt / 1.25 tsp / 1¼tsp | shown |
| rc-128 | 12 tablespoons (168g) unsalted butter, at room temperature | unsalted butter / 12 tbsp / 170g | unsalted butter / 12.0 tbsp / 12tbsp | shown |
| rc-129 | 1 1/4 cups (266g) light brown sugar, packed | light brown sugar / 5/4 cup / 270g | light brown sugar / 1.25 cup / 280g | shown |
| rc-130 | 1/4 cup (78g) light corn syrup | light corn syrup / 1/4 cup / 80g | light corn syrup / 0.25 cup / 60ml | shown |
| rc-133 | 2 cups (340g) semisweet chocolate wafers, bittersweet chocolate wafers, or semisweet chocolate chips | semisweet chocolate wafers / 2 cup / 340g | semisweet chocolate wafers / 2.0 cup / 480ml | A or B, shown |
| rc-134 | 1 cup (112g) chopped nuts, optional | nuts / 1 cup / 120g | nuts / 1.0 cup / 240ml | shown |
| rc-135 | flaky sea salt, for sprinkling on top | flaky sea salt /   / (no amount) | salt /   / (no amount) | garnish |
| rc-136 | 1½ cups plus 1 Tbsp. (200 g) all-purpose flour (spooning into measuring cups, then leveling) | all-purpose flour / 3/2 cup / 200g | plain flour / 1.5 cup / 190g | shown |
| rc-138 | ¾ tsp. (4 g) baking soda | baking soda / 3/4 tsp / 4g | bicarbonate of soda / 0.75 tsp / ¾tsp | shown |
| rc-140 | 1 cup (200 g) (packed) dark brown sugar | dark brown sugar / 1 cup / 200g | dark brown sugar / 1.0 cup / 220g | shown |
| rc-143 | 6 oz. (170 g) bittersweet chocolate (60%–70% cacao), coarsely chopped, or semisweet chocolate chips | bittersweet chocolate / 6 oz / 170g | bittersweet chocolate / 6.0 oz / 180g | A or B, shown |
| rc-167 | 2 tablespoons freshly squeezed lime juice (from about 1 lime, plus additional for serving) | lime juice / 2 tbsp / 2tbsp | lime juice / 2.0 tbsp / 2tbsp | garnish |
| rc-168 | ¼ cup chopped fresh cilantro (plus additional for serving) | cilantro / 1/4 cup / 60ml | coriander / 0.25 cup / 60ml | garnish |
| rc-225 | 1 tbsp malt syrup ((or dark syrup)) | malt syrup / 1 tbsp / 1tbsp | malt syrup / 1.0 tbsp / 1tbsp | A or B |
| rc-228 | 2 tbsp gravy browning ((can be omitted)) | gravy browning / 2 tbsp / 2tbsp | gravy browning / 2.0 tbsp / 2tbsp | optional |
| rc-230 | 2 large sprigs thyme (, or 1 teaspoon dried) | thyme / 2 sprig / 2 sprigs | thyme / 2.0 sprig / 2 sprigs | A or B |
| rc-254 | 2 tablespoons ghee (or 1 tbs butter + 1 tbs oil) | ghee / 2 tbsp / 2tbsp | ghee / 2.0 tbsp / 2tbsp | A or B |
| rc-256 | 1 1/4 teaspoons salt (or to taste) | salt / 5/4 tsp / 1¼tsp | salt / 1.25 tsp / 1¼tsp | A or B |
| rc-257 | 1 cup thickened cream (heavy or evaporated milk to save calories) | thickened cream / 1 cup / 250ml | thickened cream / 1.0 cup / 250ml | A or B |
| rc-267 | 1 tsp toasted sesame oil ((optional // or sub peanut or avocado oil)) | toasted sesame oil / 1 tsp / 1tsp | toasted sesame oil / 1.0 tsp / 1tsp | A or B |

## Flagged

| id | line | reason | right? |
|---|---|---|---|
| rc-011 | 2 rashers smoked streaky bacon | an unknown unit | yes |
| rc-033 | 2 beef stock cubes or stock pots | "A or B": one of two | yes |
| rc-040 | 2 red or yellow peppers | "A or B": one of two | yes |
| rc-042 | supermarket pack or small bunch basil | "A or B": one of two | no: supermarket pack |   | (no amount) |
| rc-055 | salt and pepper | "X and Y": may be two items | no: salt and pepper |   | (no amount) |
| rc-057 | 4 warmed corn or wheat tortillas (optional) | "A or B": one of two | no: corn | 4  | 4 |
| rc-062 | salt and freshly ground black pepper | "X and Y": may be two items | no: salt and freshly ground black pepper |   | (no amount) |
| rc-073 | groundnut or vegetable oil | "A or B": one of two | yes |
| rc-076 | 1 small handful of flaked almonds | an unknown unit | yes |
| rc-111 | 4 cloves | no ingredient name | no:  | 4 clove | 4 cloves |
| rc-116 | a small handful of coriander leaves, to serve | an unknown unit | yes |
| rc-119 | 100g white or milk chocolate chunks chopped | "A or B": one of two | no: white chocolate chunks | 100 g | 100g |
| rc-121 | baby marshmallows 2 handfuls | an amount it couldn't read | no: baby marshmallows 2 handfuls |   | (no amount) |
| rc-125 | 4 tablespoons (57g) melted butter or 1/4 cup (50g) vegetable oil | "A or B": one of two | yes |
| rc-137 | 1¼ tsp. (4 g) Diamond Crystal or ¾ tsp. (4 g) Morton kosher salt | "A or B": one of two | no: diamond crystal | 5/4 tsp | 4g |
| rc-144 | One 2- to 3-lb. farm-raised chicken, giblets removed and discarded | an amount left in the name | no: 2 | 1  | 1 |
| rc-164 | 2 pounds boneless, skinless chicken breasts or thighs | "A or B": one of two | yes |
| rc-165 | 1 (16-ounce) jar prepared salsa | an unknown unit | no: prepared salsa | 1 other | 1 |
| rc-169 | Whole wheat or corn tortillas (for serving) | "A or B": one of two | no: wheat tortillas |   | (no amount) |
| rc-182 | 1 pound 93% lean ground turkey | an amount left in the name | no: 93 % lean ground turkey | 1 lb | 460g |
| rc-201 | Remilled durum wheat semolina 2.1 cups | an amount it couldn't read | no: remilled durum wheat semolina 2.1 cups |   | (no amount) |
| rc-202 | Type 00 flour 1.3 cups | an amount it couldn't read | no: type 00 flour 1.3 cups |   | (no amount) |
| rc-203 | Spinach 8.8 oz | an amount it couldn't read | no: spinach 8.8 oz |   | (no amount) |
| rc-204 | Eggs 2 | an amount it couldn't read | no: eggs 2 |   | (no amount) |
| rc-205 | Beef (minced, coarsely ground and mixed) 10.6 oz | an amount it couldn't read | no: beef 10.6 oz |   | (no amount) |
| rc-206 | Pancetta 5.3 oz | an amount it couldn't read | no: pancetta 5.3 oz |   | (no amount) |
| rc-207 | Tomato purée 1 ¼ cup | an amount it couldn't read | no: tomato purée 1 ¼ cup |   | (no amount) |
| rc-209 | Whole milk 1 quart | an amount it couldn't read | no: whole milk 1 quart |   | (no amount) |
| rc-210 | Fine salt 1 pinch | an amount it couldn't read | no: fine salt 1 pinch |   | (no amount) |
| rc-211 | Parmigiano Reggiano PDO cheese 2.3 cups | an amount it couldn't read | no: parmigiano reggiano pdo cheese 2.3 cups |   | (no amount) |
| rc-213 | Mascarpone cheese 2.2 lbs | an amount it couldn't read | no: mascarpone cheese 2.2 lbs |   | (no amount) |
| rc-214 | Ladyfingers (about 42 pieces) 12.3 oz | an amount it couldn't read | no: ladyfingers 12.3 oz |   | (no amount) |
| rc-215 | Coffee 7.1 oz | an amount it couldn't read | no: coffee 7.1 oz |   | (no amount) |
| rc-232 | 6 whole cloves | no ingredient name | no:  | 6 clove | 6 cloves |
| rc-246 | 30g / 2 tbsp  unsalted butter OR ghee | "A or B": one of two | yes |
| rc-251 | 28 oz boneless and skinless chicken thighs or breasts (cut into bite-sized pieces) | "A or B": one of two | no: boneless and skinless chicken thighs | 28 oz | 800g |
| rc-264 | 3 Tbsp tamari or soy sauce ((plus more for veggies + to taste)) | "A or B": one of two | yes |
| rc-270 | bananas x6 | an amount it couldn't read | no: bananas x 6 |   | (no amount) |
| rc-272 | chicken thighs 1kg | an amount it couldn't read | no: chicken thighs 1 kg |   | (no amount) |
| rc-281 | semi skimmed milk 2 pints | an amount it couldn't read | no: semi skimmed milk 2 pints |   | (no amount) |
| rc-283 | mince 500g | an amount it couldn't read | no: mince 500 g |   | (no amount) |
| rc-285 | strawbs 2 punnets | an amount it couldn't read | no: strawbs 2 punnets |   | (no amount) |
