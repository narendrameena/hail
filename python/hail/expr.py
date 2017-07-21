import abc
from hail.java import scala_object, Env, jset
from hail.representation import Variant, AltAllele, Genotype, Locus, Interval, Struct, Call


class TypeCheckError(Exception):
    """
    Error thrown at mismatch between expected and supplied python types.

    :param str message: Error message
    """

    def __init__(self, message):
        self.msg = message
        super(TypeCheckError).__init__(TypeCheckError)

    def __str__(self):
        return self.msg


class Type(object):
    """
    Hail type superclass used for annotations and expression language.
    """
    __metaclass__ = abc.ABCMeta

    def __init__(self, jtype):
        self._jtype = jtype

    def __repr__(self):
        return str(self)

    def __str__(self):
        return self._jtype.toPrettyString(0, True, False)

    def __eq__(self, other):
        return self._jtype.equals(other._jtype)

    def __hash__(self):
        return self._jtype.hashCode()

    def pretty(self, indent=0, attrs=False):
        """Returns a prettily formatted string representation of the type.

        :param int indent: Number of spaces to indent.

        :param bool attrs: Print struct field attributes.

        :rtype: str
        """

        return self._jtype.toPrettyString(indent, False, attrs)

    @classmethod
    def _from_java(cls, jtype):
        # FIXME string matching is pretty hacky
        class_name = jtype.getClass().getCanonicalName()

        if class_name in __singletons__:
            return __singletons__[class_name]()
        elif class_name == 'is.hail.expr.TArray':
            return TArray._from_java(jtype)
        elif class_name == 'is.hail.expr.TSet':
            return TSet._from_java(jtype)
        elif class_name == 'is.hail.expr.TDict':
            return TDict._from_java(jtype)
        elif class_name == 'is.hail.expr.TStruct':
            return TStruct._from_java(jtype)
        else:
            raise TypeError("unknown type class: '%s'" % class_name)

    @abc.abstractmethod
    def _typecheck(self, annotation):
        """
        Raise an exception if the given annotation is not the appropriate type.

        :param annotation: value to check
        """
        return


class Singleton(type):
    _instances = {}

    def __call__(cls, *args, **kwargs):
        if cls not in cls._instances:
            cls._instances[cls] = super(Singleton, cls).__call__(*args, **kwargs)
        return cls._instances[cls]


class SingletonType(Singleton, abc.ABCMeta):
    pass


class TInt(Type):
    """
    Hail type corresponding to 32-bit integers

    .. include:: hailType.rst

    - `expression language documentation <types.html#int>`__
    - in Python, these are represented natively as Python integers

    """
    __metaclass__ = SingletonType

    def __init__(self):
        super(TInt, self).__init__(scala_object(Env.hail().expr, 'TInt'))

    def _convert_to_py(self, annotation):
        return annotation

    def _convert_to_j(self, annotation):
        if annotation:
            return Env.jutils().makeInt(annotation)
        else:
            return annotation

    def _typecheck(self, annotation):
        if annotation and not isinstance(annotation, int):
            raise TypeCheckError("TInt expected type 'int', but found type '%s'" % type(annotation))


class TLong(Type):
    """
    Hail type corresponding to 64-bit integers

    .. include:: hailType.rst

    - `expression language documentation <types.html#long>`__
    - in Python, these are represented natively as Python integers

    """
    __metaclass__ = SingletonType

    def __init__(self):
        super(TLong, self).__init__(scala_object(Env.hail().expr, 'TLong'))

    def _convert_to_py(self, annotation):
        return annotation

    def _convert_to_j(self, annotation):
        if annotation:
            return Env.jutils().makeLong(annotation)
        else:
            return annotation

    def _typecheck(self, annotation):
        if annotation and not (isinstance(annotation, long) or isinstance(annotation, int)):
            raise TypeCheckError("TLong expected type 'int' or 'long', but found type '%s'" % type(annotation))


class TFloat(Type):
    """
    Hail type corresponding to 32-bit floating point numbers

    .. include:: hailType.rst

    - `expression language documentation <types.html#float>`__
    - in Python, these are represented natively as Python floats

    """
    __metaclass__ = SingletonType

    def __init__(self):
        super(TFloat, self).__init__(scala_object(Env.hail().expr, 'TFloat'))

    def _convert_to_py(self, annotation):
        return annotation

    def _convert_to_j(self, annotation):
        # if annotation:
        #     return Env.jutils().makeFloat(annotation)
        # else:
        #     return annotation

        # FIXME: This function is unsupported until py4j-0.10.4: https://github.com/bartdag/py4j/issues/255
        raise NotImplementedError('TFloat is currently unsupported in certain operations, use TDouble instead')

    def _typecheck(self, annotation):
        if annotation and not isinstance(annotation, float):
            raise TypeCheckError("TDouble expected type 'float', but found type '%s'" % type(annotation))


class TDouble(Type):
    """
    Hail type corresponding to 64-bit floating point numbers (python default)

    .. include:: hailType.rst

    - `expression language documentation <types.html#double>`__
    - in Python, these are represented natively as Python floats

    """
    __metaclass__ = SingletonType

    def __init__(self):
        super(TDouble, self).__init__(scala_object(Env.hail().expr, 'TDouble'))

    def _convert_to_py(self, annotation):
        return annotation

    def _convert_to_j(self, annotation):
        if annotation:
            return Env.jutils().makeDouble(annotation)
        else:
            return annotation

    def _typecheck(self, annotation):
        if annotation and not isinstance(annotation, float):
            raise TypeCheckError("TDouble expected type 'float', but found type '%s'" % type(annotation))


class TString(Type):
    """
    Hail type corresponding to str

    .. include:: hailType.rst

    - `expression language documentation <types.html#string>`__
    - in Python, these are represented natively as Python unicode strings

    """
    __metaclass__ = SingletonType

    def __init__(self):
        super(TString, self).__init__(scala_object(Env.hail().expr, 'TString'))

    def _convert_to_py(self, annotation):
        return annotation

    def _convert_to_j(self, annotation):
        return annotation

    def _typecheck(self, annotation):
        if annotation and not (isinstance(annotation, str) or isinstance(annotation, unicode)):
            raise TypeCheckError("TString expected type 'str', but found type '%s'" % type(annotation))


class TBoolean(Type):
    """
    Hail type corresponding to bool

    .. include:: hailType.rst

    - `expression language documentation <types.html#boolean>`__
    - in Python, these are represented natively as Python booleans (i.e. ``True`` and ``False``)

    """
    __metaclass__ = SingletonType

    def __init__(self):
        super(TBoolean, self).__init__(scala_object(Env.hail().expr, 'TBoolean'))

    def _convert_to_py(self, annotation):
        return annotation

    def _convert_to_j(self, annotation):
        return annotation

    def _typecheck(self, annotation):
        if annotation and not isinstance(annotation, bool):
            raise TypeCheckError("TBoolean expected type 'bool', but found type '%s'" % type(annotation))


class TArray(Type):
    """
    Hail type corresponding to list

    .. include:: hailType.rst

    - `expression language documentation <types.html#array>`__
    - in Python, these are represented natively as Python sequences

    :param element_type: type of array elements
    :type element_type: :class:`.Type`

    :ivar element_type: type of array elements
    :vartype element_type: :class:`.Type`
    """

    def __init__(self, element_type):
        """
        :param :class:`.Type` element_type: Hail type of array element
        """
        jtype = scala_object(Env.hail().expr, 'TArray').apply(element_type._jtype)
        self.element_type = element_type
        super(TArray, self).__init__(jtype)

    @classmethod
    def _from_java(cls, jtype):
        t = TArray.__new__(cls)
        t.element_type = Type._from_java(jtype.elementType())
        t._jtype = jtype
        return t

    def _convert_to_py(self, annotation):
        if annotation:
            lst = Env.jutils().iterableToArrayList(annotation)
            return [self.element_type._convert_to_py(x) for x in lst]
        else:
            return annotation

    def _convert_to_j(self, annotation):
        if annotation is not None:
            return Env.jutils().arrayListToISeq(
                [self.element_type._convert_to_j(elt) for elt in annotation]
            )
        else:
            return annotation

    def _typecheck(self, annotation):
        if annotation:
            if not isinstance(annotation, list):
                raise TypeCheckError("TArray expected type 'list', but found type '%s'" % type(annotation))
            for elt in annotation:
                self.element_type._typecheck(elt)


class TSet(Type):
    """
    Hail type corresponding to set

    .. include:: hailType.rst

    - `expression language documentation <types.html#set>`__
    - in Python, these are represented natively as Python mutable sets

    :param element_type: type of set elements
    :type element_type: :class:`.Type`

    :ivar element_type: type of set elements
    :vartype element_type: :class:`.Type`
    """

    def __init__(self, element_type):
        """
        :param :class:`.Type` element_type: Hail type of set element
        """
        jtype = scala_object(Env.hail().expr, 'TSet').apply(element_type._jtype)
        self.element_type = element_type
        super(TSet, self).__init__(jtype)

    @classmethod
    def _from_java(cls, jtype):
        t = TSet.__new__(cls)
        t.element_type = Type._from_java(jtype.elementType())
        t._jtype = jtype
        return t

    def _convert_to_py(self, annotation):
        if annotation:
            lst = Env.jutils().iterableToArrayList(annotation)
            return set([self.element_type._convert_to_py(x) for x in lst])
        else:
            return annotation

    def _convert_to_j(self, annotation):
        if annotation is not None:
            return jset(
                [self.element_type._convert_to_j(elt) for elt in annotation]
            )
        else:
            return annotation

    def _typecheck(self, annotation):
        if annotation:
            if not isinstance(annotation, set):
                raise TypeCheckError("TSet expected type 'set', but found type '%s'" % type(annotation))
            for elt in annotation:
                self.element_type._typecheck(elt)


class TDict(Type):
    """
    Hail type corresponding to dict

    .. include:: hailType.rst

    - `expression language documentation <types.html#dict>`__
    - in Python, these are represented natively as Python dict

    :param key_type: type of dict keys
    :type key_type: :class:`.Type`
    :param value_type: type of dict values
    :type value_type: :class:`.Type`

    :ivar key_type: type of dict keys
    :vartype key_type: :class:`.Type`
    :ivar value_type: type of dict values
    :vartype value_type: :class:`.Type`
    """

    def __init__(self, key_type, value_type):
        jtype = scala_object(Env.hail().expr, 'TDict').apply(key_type._jtype, value_type._jtype)
        self.key_type = key_type
        self.value_type = value_type
        super(TDict, self).__init__(jtype)

    @classmethod
    def _from_java(cls, jtype):
        t = TDict.__new__(cls)
        t.key_type = Type._from_java(jtype.keyType())
        t.value_type = Type._from_java(jtype.valueType())
        t._jtype = jtype
        return t

    def _convert_to_py(self, annotation):
        if annotation:
            lst = Env.jutils().iterableToArrayList(annotation)
            d = dict()
            for x in lst:
                d[self.key_type._convert_to_py(x._1())] = self.value_type._convert_to_py(x._2())
            return d
        else:
            return annotation

    def _convert_to_j(self, annotation):
        if annotation is not None:
            return Env.jutils().javaMapToMap(
                {self.key_type._convert_to_j(k): self.value_type._convert_to_j(v) for k, v in annotation.iteritems()}
            )
        else:
            return annotation

    def _typecheck(self, annotation):
        if annotation:
            if not isinstance(annotation, dict):
                raise TypeCheckError("TDict expected type 'dict', but found type '%s'" % type(annotation))
            for k, v in annotation.iteritems():
                self.key_type._typecheck(k)
                self.value_type._typecheck(v)


class Field(object):
    """
    Helper class for :class:`.TStruct`: contains attribute names and types.

    :param str name: name of field
    :param typ: type of field
    :type typ: :class:`.Type`
    :param dict attributes: key/value attributes of field

    :ivar str name: name of field
    :ivar typ: type of field
    :vartype typ: :class:`.Type`
    """

    def __init__(self, name, typ, attributes={}):
        self.name = name
        self.typ = typ
        self.attributes = attributes


class TStruct(Type):
    """
    Hail type corresponding to :class:`hail.representation.Struct`

    .. include:: hailType.rst

    - `expression language documentation <types.html#struct>`__
    - in Python, values are instances of :class:`hail.representation.Struct`

    :param names: names of fields
    :type names: list of str
    :param types: types of fields
    :type types: list of :class:`.Type`

    :ivar fields: struct fields
    :vartype fields: list of :class:`.Field`
    """

    def __init__(self, names, types):
        """
        """

        if len(names) != len(types):
            raise ValueError('length of names and types not equal: %d and %d' % (len(names), len(types)))
        jtype = scala_object(Env.hail().expr, 'TStruct').apply(names, map(lambda t: t._jtype, types))
        self.fields = [Field(names[i], types[i]) for i in xrange(len(names))]

        super(TStruct, self).__init__(jtype)

    @classmethod
    def _from_java(cls, jtype):
        struct = TStruct.__new__(cls)
        struct._init_from_java(jtype)
        struct._jtype = jtype
        return struct

    def _init_from_java(self, jtype):

        jfields = Env.jutils().iterableToArrayList(jtype.fields())
        self.fields = [Field(f.name(), Type._from_java(f.typ()), dict(f.attrsJava())) for f in jfields]

    def _convert_to_py(self, annotation):
        if annotation:
            d = dict()
            for i, f in enumerate(self.fields):
                d[f.name] = f.typ._convert_to_py(annotation.get(i))
            return Struct(d)
        else:
            return annotation

    def _convert_to_j(self, annotation):
        if annotation is not None:
            return scala_object(Env.hail().annotations, 'Annotation').fromSeq(
                Env.jutils().arrayListToISeq(
                    [f.typ._convert_to_j(annotation.get(f.name)) for f in self.fields]
                )
            )
        else:
            return annotation

    def _typecheck(self, annotation):
        if annotation:
            if not isinstance(annotation, Struct):
                raise TypeCheckError("TStruct expected type hail.representation.Struct, but found '%s'" %
                                     type(annotation))
            for f in self.fields:
                if not (f.name in annotation):
                    raise TypeCheckError("TStruct expected fields '%s', but found fields '%s'" %
                                         ([f.name for f in self.fields], annotation.fields))
                f.typ._typecheck((annotation[f.name]))


class TVariant(Type):
    """
    Hail type corresponding to :class:`hail.representation.Variant`

    .. include:: hailType.rst

    - `expression language documentation <types.html#variant>`__
    - in Python, values are instances of :class:`hail.representation.Variant`

    """
    __metaclass__ = SingletonType

    def __init__(self):
        super(TVariant, self).__init__(scala_object(Env.hail().expr, 'TVariant'))

    def _convert_to_py(self, annotation):
        if annotation:
            return Variant._from_java(annotation)
        else:
            return annotation

    def _convert_to_j(self, annotation):
        if annotation is not None:
            return annotation._jrep
        else:
            return annotation

    def _typecheck(self, annotation):
        if annotation and not isinstance(annotation, Variant):
            raise TypeCheckError('TVariant expected type hail.representation.Variant, but found %s' %
                                 type(annotation))


class TAltAllele(Type):
    """
    Hail type corresponding to :class:`hail.representation.AltAllele`

    .. include:: hailType.rst

    - `expression language documentation <types.html#altallele>`__
    - in Python, values are instances of :class:`hail.representation.AltAllele`

    """
    __metaclass__ = SingletonType

    def __init__(self):
        super(TAltAllele, self).__init__(scala_object(Env.hail().expr, 'TAltAllele'))

    def _convert_to_py(self, annotation):
        if annotation:
            return AltAllele._from_java(annotation)
        else:
            return annotation

    def _convert_to_j(self, annotation):
        if annotation is not None:
            return annotation._jrep
        else:
            return annotation

    def _typecheck(self, annotation):
        if annotation and not isinstance(annotation, AltAllele):
            raise TypeCheckError('TAltAllele expected type hail.representation.AltAllele, but found %s' %
                                 type(annotation))


class TGenotype(Type):
    """
    Hail type corresponding to :class:`hail.representation.Genotype`

    .. include:: hailType.rst

    - `expression language documentation <types.html#genotype>`__
    - in Python, values are instances of :class:`hail.representation.Genotype`

    """
    __metaclass__ = SingletonType

    def __init__(self):
        super(TGenotype, self).__init__(scala_object(Env.hail().expr, 'TGenotype'))

    def _convert_to_py(self, annotation):
        if annotation:
            return Genotype._from_java(annotation)
        else:
            return annotation

    def _convert_to_j(self, annotation):
        if annotation is not None:
            return annotation._jrep
        else:
            return annotation

    def _typecheck(self, annotation):
        if annotation and not isinstance(annotation, Genotype):
            raise TypeCheckError('TGenotype expected type hail.representation.Genotype, but found %s' %
                                 type(annotation))


class TCall(Type):
    """
    Hail type corresponding to :class:`hail.representation.Call`

    .. include:: hailType.rst

    - `expression language documentation <types.html#call>`__
    - in Python, values are instances of :class:`hail.representation.Call`

    """
    __metaclass__ = SingletonType

    def __init__(self):
        super(TCall, self).__init__(scala_object(Env.hail().expr, 'TCall'))

    def _convert_to_py(self, annotation):
        if annotation:
            return Call._from_java(annotation)
        else:
            return annotation

    def _convert_to_j(self, annotation):
        if annotation is not None:
            return annotation._jrep
        else:
            return annotation

    def _typecheck(self, annotation):
        if annotation and not isinstance(annotation, Call):
            raise TypeCheckError('TCall expected type hail.representation.Call, but found %s' %
                                 type(annotation))


class TLocus(Type):
    """
    Hail type corresponding to :class:`hail.representation.Locus`

    .. include:: hailType.rst

    - `expression language documentation <types.html#locus>`__
    - in Python, values are instances of :class:`hail.representation.Locus`

    """
    __metaclass__ = SingletonType

    def __init__(self):
        super(TLocus, self).__init__(scala_object(Env.hail().expr, 'TLocus'))

    def _convert_to_py(self, annotation):
        if annotation:
            return Locus._from_java(annotation)
        else:
            return annotation

    def _convert_to_j(self, annotation):
        if annotation is not None:
            return annotation._jrep
        else:
            return annotation

    def _typecheck(self, annotation):
        if annotation and not isinstance(annotation, Locus):
            raise TypeCheckError('TLocus expected type hail.representation.Locus, but found %s' %
                                 type(annotation))


class TInterval(Type):
    """
    Hail type corresponding to :class:`hail.representation.Interval`

    .. include:: hailType.rst

    - `expression language documentation <types.html#interval>`__
    - in Python, values are instances of :class:`hail.representation.Interval`

    """
    __metaclass__ = SingletonType

    def __init__(self):
        super(TInterval, self).__init__(scala_object(Env.hail().expr, 'TInterval'))

    def _convert_to_py(self, annotation):
        if annotation:
            return Interval._from_java(annotation)
        else:
            return annotation

    def _convert_to_j(self, annotation):
        if annotation is not None:
            return annotation._jrep
        else:
            return annotation

    def _typecheck(self, annotation):
        if annotation and not isinstance(annotation, Interval):
            raise TypeCheckError('TInterval expected type hail.representation.Interval, but found %s' %
                                 type(annotation))


__singletons__ = {'is.hail.expr.TInt$': TInt,
                  'is.hail.expr.TLong$': TLong,
                  'is.hail.expr.TFloat$': TFloat,
                  'is.hail.expr.TDouble$': TDouble,
                  'is.hail.expr.TBoolean$': TBoolean,
                  'is.hail.expr.TString$': TString,
                  'is.hail.expr.TVariant$': TVariant,
                  'is.hail.expr.TAltAllele$': TAltAllele,
                  'is.hail.expr.TLocus$': TLocus,
                  'is.hail.expr.TGenotype$': TGenotype,
                  'is.hail.expr.TCall$': TCall,
                  'is.hail.expr.TInterval$': TInterval}

import pprint

_old_printer = pprint.PrettyPrinter

class TypePrettyPrinter(pprint.PrettyPrinter):
    def _format(self, object, stream, indent, allowance, context, level):
        if isinstance(object, Type):
            stream.write(object.pretty(self._indent_per_level))
        else:
            return _old_printer._format(self, object, stream, indent, allowance, context, level)


pprint.PrettyPrinter = TypePrettyPrinter  # monkey-patch pprint
