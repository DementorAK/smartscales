package dev.kinash.smartscales.core.formula

/**
 * What is wrong in a formula set, as a [kind] the app shows in the person's language, with [args] put into its
 * text in place of `%1$s`, `%2$s`… [english] is the text in English, for tests, logs and exceptions.
 */
data class Problem(val kind: ProblemKind, val args: List<String> = emptyList()) {
    constructor(kind: ProblemKind, vararg args: Any) : this(kind, args.map { it.toString() })

    val english: String get() = kind.english.format(*args.toTypedArray())

    override fun toString() = english
}

/** Every problem the formula engine reports; the app has a translated text for each. */
enum class ProblemKind(val english: String) {
    // The file
    NOT_OBJECT("A formula set is a JSON object"),
    NO_FORMAT("No format version"),
    UNSUPPORTED_FORMAT("Unsupported format %1\$s; this app reads format %2\$s"),

    /** The JSON reader's own words, which stay in English: an unknown key, a wrong type, a missing field. */
    MALFORMED("The file does not follow the format: %1\$s"),
    BUILTIN_ID("The built-in set has this id; a set of one's own needs another"),

    // The set
    SET_ID_TAKEN("Another enabled set has the id '%1\$s'"),

    /** In the editor: a set switched off counts too, as importing a file of that id would replace it. */
    SET_ID_USED("Another set has the id '%1\$s'"),
    ID_RESERVED("'%1\$s' cannot be an id: a reserved word"),
    ID_INVALID("'%1\$s' cannot be an id: not Latin letters, digits and _, starting with a letter or _"),
    ENGLISH_REQUIRED("English text ('en') is required"),
    STANDARD_INPUT("'%1\$s' is a standard input already"),
    INPUT_UNIT("An input is in cm, kg or none"),
    INPUT_REPLACED("Input '%1\$s' from '%2\$s' is replaced by this set"),
    INPUT_REPLACED_UNIT("Input '%1\$s' is already in '%2\$s' with another unit: values stored for it would be read wrong"),
    DEFINED_TWICE("'%1\$s' is defined twice in this set"),
    INDICATOR_REPLACED("'%1\$s' from '%2\$s' is replaced by this set"),
    PATCH_NOTHING("No indicator '%1\$s' below this set to change"),
    PATCHED_TWICE("'%1\$s' is also changed by '%2\$s'; this set's changes win"),
    HIDE_NOTHING("Nothing called '%1\$s' to hide"),
    GROUP_NAME_UNUSED("No indicators in group '%1\$s' to name"),
    PRECISION("Precision must be 0 to %1\$s"),
    PARAM_NAME("'%1\$s' cannot be a parameter name"),

    /** In the editor: two fields of one indicator name the same parameter. */
    PARAM_TWICE("Parameter '%1\$s' is given twice"),
    NOT_A_NUMBER("Not a number"),

    /** In the editor: a field that must be filled is blank. */
    REQUIRED("Fill this in"),
    ZONE_LEVELS("Needs one level more than bounds: %1\$s"),
    ZONE_BOUNDS("Bounds must go up"),
    ZONE_LABELS("Needs one label per level"),
    GROUP_IS_INDICATOR("'%1\$s' is both a group and an indicator"),
    GROUP_IS_INPUT("Group '%1\$s' has the name of an input"),
    INDICATOR_IS_INPUT("Indicator '%1\$s' has the name of an input"),
    PARAM_HIDES_INPUT("Parameter '%1\$s' would hide the input of that name"),
    PARAM_HIDES_INDICATOR("Parameter '%1\$s' hides the indicator of that name in this formula"),
    PARAM_UNUSED("Parameter is not used"),
    MUST_GIVE_NUMBER("Must give a number"),
    MUST_GIVE_CONDITION("Must give a yes/no condition"),
    LOOP("Formulas refer to each other in a loop: %1\$s"),

    // A formula
    TOO_LONG("Formula is longer than %1\$s characters"),
    TOO_DEEP("Formula is too deeply nested"),
    BAD_NUMBER("Bad number"),
    UNEXPECTED_CHARACTER("Unexpected character '%1\$s'"),
    EMPTY("Empty formula"),
    UNEXPECTED("Unexpected '%1\$s'"),
    CHAINED_COMPARISON("Comparisons cannot be chained; use 'and'"),
    ENDS_EARLY("Formula ends too early"),
    EXPECTED_BUT_END("Expected '%1\$s' but found the end of the formula"),
    EXPECTED_BUT("Expected '%1\$s' but found '%2\$s'"),
    FUNCTION_NOT_CALLED("'%1\$s' is a function; call it with ( )"),
    UNKNOWN_NAME("Unknown name '%1\$s'"),
    UNKNOWN_FUNCTION("Unknown function '%1\$s'"),
    COMPARE_TYPES("Cannot compare a number with a yes/no value"),
    ARGUMENTS_ONE("'%1\$s' takes 1 argument"),
    ARGUMENTS_EXACTLY("'%1\$s' takes %2\$s arguments"),
    ARGUMENTS_AT_LEAST("'%1\$s' takes at least %2\$s arguments"),
    ARGUMENTS_RANGE("'%1\$s' takes %2\$s to %3\$s arguments"),
    IF_ARGUMENTS("'if' takes a condition and a value, any more such pairs, then the value otherwise"),
    COALESCE_ARGUMENTS("'coalesce' needs at least one value"),
    HAS_ARGUMENTS("'has' takes one value"),
    EXPECTED_NUMBER("Expected a number"),
    EXPECTED_CONDITION("Expected a yes/no value"),
}
