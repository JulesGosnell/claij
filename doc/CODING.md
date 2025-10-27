We should also have some coding guidelines documented somewhere - I'll start:

I like symmetry - open/close, start/stop - I like the names to be similar length

I like short (?anglo-saxon?) names rather than long (latin/greek) names

I don't like boilerplate

I generally inline things that are only going to be used once unless things are getting too complicated

I always ask myself whether something can be simpler

I try to write idiomatic, functional (pure fns, immutable vars/collections)

I try to decompose complex things into smaller simpler reusable things with higher order functions

I try not to violate the "principle of least surpise"

I try to think clearly and keep the number of concerns in any single piece of code down to a minimum (composition allows these to be recombined later)

I like things to be data driven

1 unit of time [re]thinking is worth 100 units of time coding

I keep comments to a minimum but do not exclude them on religious grounds


